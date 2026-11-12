import java.util.Random;

/**
 * The escorts' brains, running on their OWN thread at 5 Hz, independently of the players.
 * The AI only decides (target heading, speed, when to drop); the GameLoop does the moving.
 *
 *   PATROL  - weave ahead of the convoy, listening
 *   SEARCH  - something was heard: move onto the last known position and cast about
 *   RUN_IN  - sprint over the target. Fast propellers make the escort DEAF while it does this.
 *   DROP    - release a pattern of charges
 *   OVER    - carry on past, because the charges need time to sink
 *   TURN    - come about, slow down, listen again, and attack once more
 *
 * That cycle is the whole point: a real escort does not attack once and leave. It keeps
 * coming back, and the only reason it ever loses you is that it cannot hear while it is
 * running in. That gap is the player's chance to slip away.
 */
public class EnemyAI implements Runnable {
    private static final double DT = 0.2;
    private final GameState state;
    private final Random rnd = new Random();

    public EnemyAI(GameState state) {
        this.state = state;
    }

    @Override
    public void run() {
        Log.info("Enemy AI online");
        while (true) {
            state.lock.lock();
            try {
                if (state.phase.equals("PLAYING")) {
                    zigzag();
                    for (GameState.Ship d : state.ships) {
                        if (d.alive && d.type.equals("DESTROYER")) think(d);
                    }
                }
            } finally {
                state.lock.unlock();
            }
            try { Thread.sleep((long) (DT * 1000)); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * The convoy commodore's zigzag: every minute or two all merchants alter course together,
     * so a firing solution worked out on the old leg goes stale. Once the convoy knows it is
     * under attack the legs get shorter. Base course is west (270).
     */
    private void zigzag() {
        state.zigTimer -= DT;
        if (state.zigTimer > 0) return;
        double[] legs = {-30, -15, 0, 15, 30};
        double next;
        do { next = legs[rnd.nextInt(legs.length)]; } while (next == state.zigOffset);
        state.zigOffset = next;
        state.zigTimer = state.reinforced ? 45 + rnd.nextDouble() * 40 : 80 + rnd.nextDouble() * 70;
        for (GameState.Ship m : state.ships) {
            if (m.alive && m.cls != 3) m.targetHeading = GameState.norm(270 + next);
        }
    }

    private void think(GameState.Ship d) {
        boolean heard = listen(d);
        d.cooldown = Math.max(0, d.cooldown - DT);
        d.phaseTimer = Math.max(0, d.phaseTimer - DT);

        switch (d.aiState) {
            case "PATROL" -> patrol(d);
            case "SEARCH" -> search(d, heard);
            case "RUN_IN" -> runIn(d);
            case "OVER"   -> overshoot(d);
            case "TURN"   -> turn(d, heard);
            default -> d.aiState = "PATROL";
        }
    }

    /**
     * Can this escort hear the submarine right now?
     * Updates lastKnown position and contactQuality as a side effect.
     */
    private boolean listen(GameState.Ship d) {
        // Slant range, not map distance: the escort is on the surface and the boat is beneath it,
        // so depth is real separation. Sitting directly under a destroyer at 150 m is still 150 m away.
        double flat = Math.hypot(d.x - state.subX, d.y - state.subY);
        double dist = Math.hypot(flat, state.depth);
        double range = 350 + 1900 * state.noise();

        // Its own propellers drown out the hydrophones during an attack run.
        if (d.speed > 8) range *= 0.25;
        // Below the thermal layer sound bends away from the surface: the boat's best hiding place.
        if (state.depth > GameState.THERMOCLINE) range *= 0.28;
        // Stopped, pumps off, nobody hammering: barely a sound to find.
        if (state.silentRunning()) range *= 0.45;
        // A periscope leaves a feather of wake, and a surfaced boat is simply visible.
        // A raised periscope leaves a feather of wake, bigger the faster the boat is moving.
        if (state.scopeUp && state.depth >= 1) range = Math.max(range, 1300 + 450 * state.speedOrder);
        // A surfaced boat is simply visible.
        if (state.depth < 1) range = Math.max(range, 4000);

        if (dist < range) {
            double err = 30 * (1 - d.contactQuality);
            d.lastKnownX = state.subX + rnd.nextGaussian() * err;
            d.lastKnownY = state.subY + rnd.nextGaussian() * err;
            d.sinceContact = 0;
            d.contactQuality = Math.min(1, d.contactQuality + 0.18 * DT);
            return true;
        }
        d.sinceContact += DT;
        d.contactQuality = Math.max(0, d.contactQuality - 0.07 * DT);
        return false;
    }

    /** Weave back and forth ahead of the convoy. */
    private void patrol(GameState.Ship d) {
        double cx = 0, cy = 0; int n = 0;
        for (GameState.Ship s : state.ships) {
            if (s.alive && s.type.equals("MERCHANT")) { cx += s.x; cy += s.y; n++; }
        }
        if (n == 0) { cx = d.x; cy = d.y; } else { cx /= n; cy /= n; }
        d.patrolT += DT;
        double tx = cx - 600;                               // stay ahead of the westbound convoy
        double ty = cy - 400 + 600 * Math.sin(d.patrolT / 15);
        d.speed = 5;
        d.targetHeading = GameState.bearing(d.x, d.y, tx, ty);

        if (d.sinceContact < 1) {
            d.aiState = "SEARCH";
            state.events.add("EVENT|DESTROYER_HUNTING|" + d.name + " has turned toward you");
        }
    }

    /** Hunting, but not yet in position: close on the last known spot at a speed that still hears. */
    private void search(GameState.Ship d, boolean heard) {
        d.speed = 6.5;
        double dist = Math.hypot(d.x - d.lastKnownX, d.y - d.lastKnownY);

        if (dist > 150) {
            d.targetHeading = GameState.bearing(d.x, d.y, d.lastKnownX, d.lastKnownY);
        } else {
            // Cast about in a widening circle around the last contact.
            d.patrolT += DT;
            double r = 200 + d.sinceContact * 18;
            double ax = d.lastKnownX + Math.cos(d.patrolT * 0.25) * r;
            double ay = d.lastKnownY + Math.sin(d.patrolT * 0.25) * r;
            d.targetHeading = GameState.bearing(d.x, d.y, ax, ay);
        }

        // Good enough contact and close enough: begin the run.
        if (heard && dist < 1400 && d.contactQuality > 0.35 && d.cooldown <= 0 && d.chargesLeft > 0) {
            d.aiState = "RUN_IN";
            state.events.add("EVENT|ATTACK_RUN|" + d.name + " is running in");
        }
        if (d.sinceContact > 100) {
            d.aiState = "PATROL";
            d.contactQuality = 0;
            state.events.add("EVENT|DESTROYER_LOST_CONTACT|" + d.name + " has given up the search");
        }
    }

    /** Full speed over the target. Deaf while it does this. */
    private void runIn(GameState.Ship d) {
        d.speed = 12;
        d.targetHeading = GameState.bearing(d.x, d.y, d.lastKnownX, d.lastKnownY);
        if (Math.hypot(d.x - d.lastKnownX, d.y - d.lastKnownY) < 90) {
            dropPattern(d);
            d.aiState = "OVER";
            d.phaseTimer = 6;
        }
    }

    /** Charges are sinking; the escort has to keep clear of its own explosions. */
    private void overshoot(GameState.Ship d) {
        d.speed = 12;
        if (d.phaseTimer <= 0) {
            d.aiState = "TURN";
            d.phaseTimer = 14;
            d.targetHeading = GameState.norm(d.heading + 180);
        }
    }

    /** Come about slowly enough to hear again, then re-attack or resume the search. */
    private void turn(GameState.Ship d, boolean heard) {
        d.speed = 4;
        d.targetHeading = GameState.bearing(d.x, d.y, d.lastKnownX, d.lastKnownY);
        boolean linedUp = Math.abs(GameState.angleDiff(d.targetHeading, d.heading)) < 25;
        if (linedUp && d.phaseTimer <= 0) {
            if (heard && d.chargesLeft > 0) {
                d.aiState = "RUN_IN";
            } else {
                d.aiState = "SEARCH";
                if (d.chargesLeft <= 0) {
                    state.events.add("EVENT|CHARGES_EXHAUSTED|" + d.name + " has run out of depth charges");
                }
            }
        }
    }

    /**
     * A pattern of charges set to the depth the escort THINKS the boat is at.
     * Good contact means a tight, accurate pattern; poor contact scatters it.
     */
    private void dropPattern(GameState.Ship d) {
        int count = Math.min(d.chargesLeft, 5);
        double spread = 30 + 70 * (1 - d.contactQuality);
        double guessDepth = Math.max(10, state.depth + rnd.nextGaussian() * 40 * (1 - d.contactQuality));
        for (int i = 0; i < count; i++) {
            GameState.DepthCharge c = new GameState.DepthCharge();
            double along = (i - (count - 1) / 2.0) * 40;
            c.x = d.x + Math.sin(Math.toRadians(d.heading)) * along + rnd.nextGaussian() * spread * 0.3;
            c.y = d.y + Math.cos(Math.toRadians(d.heading)) * along + rnd.nextGaussian() * spread * 0.3;
            c.setDepth = guessDepth;
            c.timer = guessDepth / 8 + 1;                   // charges sink at about 8 m/s
            state.charges.add(c);
        }
        d.chargesLeft -= count;
        d.cooldown = 5;
        state.events.add("EVENT|CHARGES_DROPPED|Splashes overhead");
    }
}
