import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ONE shared world that every thread reads and writes.
 *
 * RULE: any code that touches these fields must hold `lock`.
 *   - ClientHandler threads change orders (applyCommand)
 *   - GameLoop thread moves everything (step) and reads it (snapshot)
 *   - EnemyAI thread steers the escorts
 *   - RepairCrew thread mends damaged compartments
 * Without the lock, e.g. the motors and the bilge pumps could both read battery = 10,
 * both subtract, and one of the drains would silently vanish (a race condition).
 *
 * Coordinates: metres on a flat sea. Heading/bearing 0 = north (+y), 90 = east (+x).
 */
public class GameState {
    public final ReentrantLock lock = new ReentrantLock();
    /** Producer-consumer queue: any thread adds events, the GameLoop broadcasts them. */
    public final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    /** Repair orders from the engineer, consumed by the RepairCrew thread (it BLOCKS when empty). */
    public final LinkedBlockingQueue<Integer> repairQueue = new LinkedBlockingQueue<>();

    // ---- tuning ----
    // Submerged speeds for a Type VII: electric motors are slow. Roughly 0 / 3 / 6 / 8 knots.
    static final double[] SPEED_MS = {0, 1.5, 3.0, 4.2};        // engine order 0=stop 1=slow 2=half 3=full
    // On the surface the diesels give about 17 knots, which is how a U-boat really got ahead
    // of a convoy. Fast but visible: that is the trade the commander has to make.
    static final double SURFACE_FACTOR = 2.2;
    static final double[] BATTERY_DRAIN = {0, 0.08, 0.25, 0.6}; // % per second, submerged
    static final double[] HULL_NOISE = {0.10, 0.30, 0.60, 1.00};// how loud each engine order is
    static final double PUMP_DRAIN = 0.5;
    static final double PERISCOPE_DEPTH = 15;
    static final double MAX_FIRING_DEPTH = 20;
    static final double THERMOCLINE = 80;                       // below this, sound bends away: you are harder to hear
    static final double CRUSH_DEPTH = 220;
    static final double TORPEDO_SPEED = 20, TORPEDO_RANGE = 4000, HIT_RADIUS = 40;
    static final double CONVOY_ESCAPE_X = -7000;
    static final double RELOAD_SECONDS = 25;
    /** Sink at least this much and the patrol counts as a success even if the convoy gets away. */
    static final int WIN_TONNAGE = 9000;
    static final int[] START_TORPEDOES = {8, 6, 5};             // by difficulty: easy / normal / hard
    static final int[] ESCORT_CHARGES = {20, 30, 40};
    public static final String[] DIFFICULTY_NAMES = {"EASY", "NORMAL", "HARD"};

    /** The four compartments, bow to stern. */
    public static final String[] COMPARTMENTS = {"BOW ROOM", "CONTROL", "ENGINE", "AFT ROOM"};

    // ---- submarine ----
    double subX, subY, heading, targetHeading, depth, targetDepth;
    int speedOrder, torpedoes;
    double battery, hull;
    double reload;                      // seconds until the next tube is ready
    boolean pumpsOn;
    double hydroBearing, periscopeBearing;
    boolean scopeUp;                    // a raised periscope sees, and can be seen
    double lastHullReport = 100;

    /**
     * TRAINING shows every station the true tactical picture. REALISTIC sends only what a
     * 1943 crew could sense. The filtering happens HERE, on the server: in realistic mode the
     * true ranges and courses never leave this machine, so no client can display them.
     */
    boolean training = true;

    // ---- damage control ----
    final double[] compHealth = new double[4];
    final double[] compFlood = new double[4];
    int repairing = -1;                 // compartment the crew is working on, -1 = idle
    double repairProgress;

    // ---- world ----
    String phase, reason, cause = "";   // phase: LOBBY, PLAYING, WON, LOST. cause drives the end animation
    int difficulty = 1;
    int fired, hits, dcHits;            // statistics for the debrief screen
    long tick;
    int tonnageSunk;
    boolean reinforced;                 // has the second escort been called in?
    double zigTimer, zigOffset;         // the convoy zigzags to spoil firing solutions
    final List<Ship> ships = new ArrayList<>();
    final List<Torpedo> torps = new ArrayList<>();
    final List<DepthCharge> charges = new ArrayList<>();

    static class Ship {
        int id; String type; String name;
        double x, y, heading, targetHeading, speed;
        int tonnage; double loudness;
        int cls;                        // recognition class, index into CLASS_* below
        boolean alive = true;
        // escort AI memory
        String aiState = "PATROL";
        double lastKnownX, lastKnownY, sinceContact, cooldown, patrolT;
        double contactQuality;          // 0..1, how well it has you pinned
        double phaseTimer;              // time left in the current attack phase
        int chargesLeft = 30;
    }
    static class Torpedo { double x, y, heading, traveled; }

    // Recognition classes: 0 large freighter, 1 medium freighter, 2 small engines-aft freighter, 3 destroyer.
    static final double[] CLASS_MAST = {30, 24, 18, 22};          // masthead height, metres
    static final double[] CLASS_LENGTH = {140, 120, 90, 100};     // metres
    static final double[] CLASS_RPM_PER_KNOT = {12, 14, 17, 16};  // propeller revolutions per knot

    static double knots(double ms) { return ms * 1.944; }
    static double rpmOf(Ship s) { return knots(s.speed) * CLASS_RPM_PER_KNOT[s.cls]; }

    /** Events only a training crew is told. A real crew has to work these out by ear. */
    static final java.util.Set<String> TRAINING_ONLY = java.util.Set.of(
            "TORPEDO_HIT", "TORPEDO_MISS", "DESTROYER_HUNTING", "ATTACK_RUN",
            "DESTROYER_LOST_CONTACT", "CHARGES_EXHAUSTED", "REINFORCEMENT");
    static class DepthCharge { double x, y, setDepth, timer; }

    public GameState() { reset(); }

    /** Starts a fresh patrol. Caller must hold the lock (the constructor is the exception). */
    void reset() {
        subX = 0; subY = 0; heading = targetHeading = 45;
        depth = targetDepth = 0;
        speedOrder = 1; torpedoes = 6; reload = 0;
        battery = 100; hull = 100; pumpsOn = false;
        hydroBearing = 0; periscopeBearing = 0; scopeUp = false; lastHullReport = 100;
        for (int i = 0; i < 4; i++) { compHealth[i] = 100; compFlood[i] = 0; }
        repairing = -1; repairProgress = 0;
        repairQueue.clear();
        phase = "LOBBY"; reason = ""; cause = ""; tick = 0; tonnageSunk = 0; reinforced = false;
        fired = 0; hits = 0; dcHits = 0;
        zigTimer = 70; zigOffset = 0;
        torps.clear(); charges.clear(); ships.clear();

        String[] names = {"SS Ardmore", "SS Calloway", "SS Henley"};
        int[] tons = {5200, 7800, 4100};
        int[] classes = {1, 0, 2};
        for (int i = 0; i < 3; i++) {
            Ship m = new Ship();
            m.id = i + 1; m.type = "MERCHANT"; m.name = names[i];
            m.x = 2500 + i * 350; m.y = 4500;
            m.heading = m.targetHeading = 270; m.speed = 3;
            m.tonnage = tons[i]; m.loudness = 1.0; m.cls = classes[i];
            ships.add(m);
        }
        ships.add(escort(4, "HMS Vigilant", 2800, 4000));
        events.add("EVENT|RESET|New patrol started");
    }

    /** Lobby -> mission. Applies the chosen difficulty. Caller must hold the lock. */
    void start() {
        if (!phase.equals("LOBBY")) return;
        torpedoes = START_TORPEDOES[difficulty];
        for (Ship s : ships) if (s.type.equals("DESTROYER")) s.chargesLeft = ESCORT_CHARGES[difficulty];
        phase = "PLAYING";
        events.add("EVENT|MISSION_START|" + DIFFICULTY_NAMES[difficulty]);
    }

    /** Safe to call from any thread. */
    public String phase() {
        lock.lock();
        try { return phase; } finally { lock.unlock(); }
    }

    private Ship escort(int id, String name, double x, double y) {
        Ship d = new Ship();
        d.id = id; d.type = "DESTROYER"; d.name = name;
        d.x = x; d.y = y; d.heading = d.targetHeading = 270; d.speed = 5;
        d.tonnage = 1400; d.loudness = 1.5; d.cls = 3;
        d.chargesLeft = ESCORT_CHARGES[difficulty];
        return d;
    }

    // =====================================================================
    // Commands from players (called by ClientHandler threads)
    // =====================================================================
    /** @return null if accepted, otherwise an error message for that player. */
    public String applyCommand(String station, String action, double value) {
        lock.lock();
        try {
            action = action.toUpperCase();
            if (action.equals("RESTART")) { reset(); return null; }
            if (action.equals("TRAINING")) {
                training = value != 0;
                events.add("EVENT|MODE|" + (training ? "Training aids ON" : "Realistic: 1943 instruments only"));
                return null;
            }
            if (action.equals("DIFFICULTY")) {
                if (!phase.equals("LOBBY")) return "Difficulty can only be changed in the lobby";
                difficulty = (int) clamp(value, 0, 2);
                return null;
            }
            if (phase.equals("LOBBY")) return "The patrol has not started yet - press READY";
            if (!phase.equals("PLAYING")) return "Patrol over. Send CMD|RESTART";

            boolean komm = station.equals("KOMM") || station.equals("ALL");
            boolean eng = station.equals("ENG") || station.equals("ALL");
            String denied = "Station " + station + " cannot do " + action;

            switch (action) {
                case "PERISCOPE" -> {
                    if (!komm) return denied;
                    periscopeBearing = norm(value);
                }
                case "SCOPE" -> {
                    if (!komm) return denied;
                    if (value != 0 && depth > PERISCOPE_DEPTH) return "Too deep to raise the periscope";
                    scopeUp = value != 0;
                }
                case "FIRE" -> {
                    if (!komm) return denied;
                    if (compHealth[0] < 30) return "Bow room wrecked - tubes out of action";
                    if (depth > MAX_FIRING_DEPTH) return "Too deep to fire (max " + (int) MAX_FIRING_DEPTH + " m)";
                    if (reload > 0) return "Tube still loading (" + (int) Math.ceil(reload) + " s)";
                    if (torpedoes <= 0) return "No torpedoes left";
                    torpedoes--; fired++;
                    reload = RELOAD_SECONDS;
                    Torpedo t = new Torpedo();
                    t.x = subX; t.y = subY; t.heading = norm(value);
                    torps.add(t);
                    events.add("EVENT|TORPEDO_FIRED|" + (int) t.heading);
                }
                case "SPEED" -> {
                    if (!eng) return denied;
                    int o = (int) value;
                    if (o < 0 || o > 3) return "Speed order must be 0-3";
                    if (o > maxSpeedOrder()) return "Engine room damaged - cannot exceed order " + maxSpeedOrder();
                    speedOrder = o;
                }
                case "DEPTH" -> {
                    if (!eng) return denied;
                    targetDepth = clamp(value, 0, 250);
                }
                case "HEADING" -> {
                    if (!eng) return denied;
                    targetHeading = norm(value);
                }
                case "PUMPS" -> {
                    if (!eng) return denied;
                    if (value != 0 && battery <= 0) return "Battery flat - pumps dead";
                    pumpsOn = value != 0;
                }
                case "HYDRO" -> {
                    if (!eng) return denied;
                    hydroBearing = norm(value);
                }
                case "REPAIR" -> {
                    if (!eng) return denied;
                    int c = (int) value;
                    if (c < 0 || c > 3) return "Compartment must be 0-3";
                    if (compHealth[c] >= 99.5 && compFlood[c] < 1) return COMPARTMENTS[c] + " needs no work";
                    if (repairing == c || repairQueue.contains(c)) return "Crew already assigned to " + COMPARTMENTS[c];
                    repairQueue.add(c);       // the RepairCrew thread is blocked waiting on this
                    events.add("EVENT|REPAIR_ORDERED|" + COMPARTMENTS[c]);
                }
                default -> { return "Unknown action " + action; }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** A wrecked engine room caps how fast the boat can be driven. */
    int maxSpeedOrder() {
        if (compHealth[2] < 20) return 1;
        if (compHealth[2] < 50) return 2;
        return 3;
    }

    /** Total flooding across the boat, as a percentage. */
    double floodingTotal() {
        double sum = 0;
        for (double f : compFlood) sum += f;
        return sum / 4;
    }

    /** How loud the boat is right now: what the escorts are listening for. */
    double noise() {
        double n = HULL_NOISE[Math.min(speedOrder, 3)];
        if (pumpsOn) n += 0.35;
        if (repairing >= 0) n += 0.20;          // hammering in a flooded compartment carries
        n += floodingTotal() / 600.0;
        return n;
    }

    /** Stopped, pumps off, no repair work: the boat is a hole in the water. */
    boolean silentRunning() {
        return speedOrder == 0 && !pumpsOn && repairing < 0;
    }

    // =====================================================================
    // Physics (called by GameLoop thread WITH the lock held)
    // =====================================================================
    void step(double dt) {
        if (!phase.equals("PLAYING")) return;
        tick++;

        // A damaged control room makes the planes sluggish.
        double planeRate = compHealth[1] < 40 ? 1.0 : 2.0;
        heading = norm(heading + clamp(angleDiff(targetHeading, heading), -4 * dt, 4 * dt));
        depth = Math.max(0, depth + clamp(targetDepth - depth, -planeRate * dt, planeRate * dt));

        // Water in the boat is dead weight: past half flooded she starts to sink on her own.
        double flood = floodingTotal();
        if (flood > 50) depth += (flood - 50) * 0.01 * dt;

        if (reload > 0) reload = Math.max(0, reload - dt);
        if (depth > PERISCOPE_DEPTH) scopeUp = false;       // the scope cannot stay up below 15 m

        // Battery: diesels recharge on the surface; electric motors + pumps drain it underwater.
        boolean surfaced = depth < 1;
        double drain = surfaced ? -0.4 : BATTERY_DRAIN[speedOrder];
        if (pumpsOn) drain += PUMP_DRAIN;
        battery = clamp(battery - drain * dt, 0, 100);
        if (battery <= 0 && pumpsOn) {
            pumpsOn = false;
            events.add("EVENT|PUMPS_FAILED|Battery flat");
        }
        int effectiveOrder = (battery <= 0 && !surfaced) ? 0 : Math.min(speedOrder, maxSpeedOrder());
        double v = SPEED_MS[effectiveOrder] * (surfaced ? SURFACE_FACTOR : 1.0);
        subX += Math.sin(Math.toRadians(heading)) * v * dt;
        subY += Math.cos(Math.toRadians(heading)) * v * dt;

        // Hull and flooding, per compartment.
        if (depth > CRUSH_DEPTH) {
            hull -= 5 * dt;
            for (int i = 0; i < 4; i++) compFlood[i] += 1.2 * dt;
        }
        for (int i = 0; i < 4; i++) {
            if (compHealth[i] < 100) {
                // deeper water forces more through a damaged seam
                compFlood[i] += (100 - compHealth[i]) * 0.004 * (1 + depth / 120) * dt;
            }
            compFlood[i] = clamp(compFlood[i], 0, 100);
        }
        if (pumpsOn) {
            int worst = worstFlooded();
            if (worst >= 0) compFlood[worst] = Math.max(0, compFlood[worst] - 4.0 * dt);
        }

        // Ships.
        for (Ship s : ships) {
            if (!s.alive) continue;
            s.heading = norm(s.heading + clamp(angleDiff(s.targetHeading, s.heading), -3 * dt, 3 * dt));
            s.x += Math.sin(Math.toRadians(s.heading)) * s.speed * dt;
            s.y += Math.cos(Math.toRadians(s.heading)) * s.speed * dt;
        }

        // Torpedoes.
        for (Iterator<Torpedo> it = torps.iterator(); it.hasNext(); ) {
            Torpedo t = it.next();
            double d = TORPEDO_SPEED * dt;
            t.x += Math.sin(Math.toRadians(t.heading)) * d;
            t.y += Math.cos(Math.toRadians(t.heading)) * d;
            t.traveled += d;
            Ship hit = null;
            for (Ship s : ships) {
                // A longer ship is a bigger target.
                if (s.alive && Math.hypot(s.x - t.x, s.y - t.y) < CLASS_LENGTH[s.cls] / 2.8) { hit = s; break; }
            }
            if (hit != null) {
                hit.alive = false;
                tonnageSunk += hit.tonnage; hits++;
                events.add("EVENT|TORPEDO_HIT|" + hit.name + " (" + hit.tonnage + " t) sunk");
                // What the crew actually gets: a bang after so many seconds, then a hull breaking up.
                events.add("EVENT|DETONATION|" + Math.round(t.traveled / TORPEDO_SPEED));
                alertEscorts(subX, subY, 0.8);   // the explosion gives away roughly where we are
                callReinforcement();
                it.remove();
            } else if (t.traveled > TORPEDO_RANGE) {
                events.add("EVENT|TORPEDO_MISS|Torpedo ran out of fuel");
                it.remove();
            }
        }

        // Depth charges sink, then explode.
        for (Iterator<DepthCharge> it = charges.iterator(); it.hasNext(); ) {
            DepthCharge c = it.next();
            c.timer -= dt;
            if (c.timer > 0) continue;
            it.remove();
            double dist = Math.hypot(Math.hypot(c.x - subX, c.y - subY), c.setDepth - depth);
            if (dist < 80) {
                double dmg = (80 - dist) * 0.8;
                hull = Math.max(0, hull - dmg * 0.5);
                dcHits++;
                int comp = (int) (Math.random() * 4);
                compHealth[comp] = Math.max(0, compHealth[comp] - dmg);
                compFlood[comp] = clamp(compFlood[comp] + dmg * 0.25, 0, 100);
                events.add("EVENT|DEPTH_CHARGE_HIT|" + Math.round(dmg) + "|" + COMPARTMENTS[comp]);
            } else {
                events.add("EVENT|DEPTH_CHARGE_NEAR|" + (dist < 200 ? "close aboard" : "some way off"));
            }
        }

        // Nobody aboard can read "hull 43%". What they get is the boat complaining.
        if (hull < 60 && lastHullReport >= 60) events.add("EVENT|HULL_REPORT|Frames are groaning");
        if (hull < 30 && lastHullReport >= 30) events.add("EVENT|HULL_REPORT|Rivets are popping - she cannot take much more");
        lastHullReport = hull;

        // Win / lose.
        boolean merchantsLeft = false;
        for (Ship s : ships) {
            if (s.alive && s.type.equals("MERCHANT")) {
                merchantsLeft = true;
                if (s.x < CONVOY_ESCAPE_X) {
                    if (tonnageSunk >= WIN_TONNAGE) end("WON", "The convoy escaped, but " + tonnageSunk + " tons went down", "PARTIAL");
                    else end("LOST", "The convoy escaped with " + tonnageSunk + " tons sunk (need " + WIN_TONNAGE + ")", "ESCAPED");
                }
            }
        }
        if (!merchantsLeft) end("WON", "Convoy destroyed: " + tonnageSunk + " tons sunk", "ALL");
        if (hull <= 0) end("LOST", "Hull destroyed", "HULL");
        if (floodingTotal() >= 100) end("LOST", "The boat flooded and sank", "FLOOD");
        if (depth > CRUSH_DEPTH + 60) end("LOST", "Crushed below test depth", "CRUSH");
    }

    int worstFlooded() {
        int worst = -1;
        double max = 0.5;
        for (int i = 0; i < 4; i++) if (compFlood[i] > max) { max = compFlood[i]; worst = i; }
        return worst;
    }

    private void end(String result, String why, String why2) {
        if (!phase.equals("PLAYING")) return;
        phase = result; reason = why; cause = why2;
        events.add("EVENT|GAME_OVER|" + result + "|" + why + "|" + why2);
    }

    /** A second escort joins once the convoy knows it is under attack. */
    void callReinforcement() {
        if (reinforced) return;
        reinforced = true;
        double cx = 0, cy = 0; int n = 0;
        for (Ship s : ships) if (s.alive && s.type.equals("MERCHANT")) { cx += s.x; cy += s.y; n++; }
        if (n == 0) return;
        Ship d = escort(9, "HMS Petard", cx / n + 900, cy / n + 700);
        d.lastKnownX = subX; d.lastKnownY = subY;
        d.aiState = "SEARCH";
        ships.add(d);
        events.add("EVENT|REINFORCEMENT|A second escort is closing from the convoy");
    }

    void alertEscorts(double x, double y, double quality) {
        for (Ship s : ships) {
            if (!s.alive || !s.type.equals("DESTROYER")) continue;
            s.lastKnownX = x; s.lastKnownY = y; s.sinceContact = 0;
            s.contactQuality = Math.max(s.contactQuality, quality);
            if (s.aiState.equals("PATROL")) {
                s.aiState = "SEARCH";
                events.add("EVENT|DESTROYER_HUNTING|" + s.name + " has turned toward you");
            }
        }
    }

    // =====================================================================
    // Per-station snapshots (called by GameLoop WITH the lock held).
    // Each station only receives the information its crew member would really have.
    // =====================================================================
    String snapshot(String station) {
        boolean surfaced = depth < 1;
        double charge = Math.sqrt(battery / 100.0);
        double amps = surfaced ? -400 : new double[]{0, 120, 380, 900}[Math.min(speedOrder, 3)];
        if (pumpsOn) amps += 260;

        Json j = new Json();
        j.str("phase", phase).str("reason", reason).num("tick", tick)
         .bool("training", training)
         .num("depth", depth).num("targetDepth", targetDepth)
         .num("heading", heading).num("targetHeading", targetHeading)
         .num("speed", speedOrder).num("maxSpeed", maxSpeedOrder())
         .num("ownX", subX).num("ownY", subY)              // dead reckoning: the navigator's own plot
         .num("volts", 170 + 50 * charge).num("amps", amps)
         .bool("pumps", pumpsOn).bool("scopeUp", scopeUp)
         .num("torpedoes", torpedoes).num("reload", reload)
         .num("tonnage", tonnageSunk).str("cause", cause).num("difficulty", difficulty)
         .num("winTonnage", WIN_TONNAGE).num("fired", fired).num("hits", hits).num("dcHits", dcHits).num("elapsed", tick / 20)
         .num("asdic", asdicLevel()).num("screws", screwsLevel());
        if (training) {
            j.num("battery", battery).num("hull", hull).num("flooding", floodingTotal())
             .bool("silent", silentRunning()).bool("belowLayer", depth > THERMOCLINE)
             .num("torpsInWater", torps.size());
        }

        boolean all = station.equals("ALL");
        if (station.equals("KOMM") || all) {
            // Eyes: the bridge watch when surfaced, the periscope when it is up and shallow enough.
            boolean canSee = surfaced || (scopeUp && depth <= PERISCOPE_DEPTH);
            j.bool("periscopeUp", canSee).num("periscope", periscopeBearing);
            StringBuilder arr = new StringBuilder("[");
            for (Ship s : ships) {
                if (!s.alive || !canSee) continue;
                double range = Math.hypot(s.x - subX, s.y - subY);
                if (range > 9000) continue;
                if (arr.length() > 1) arr.append(',');
                double brg = bearing(subX, subY, s.x, s.y);
                // Angle on the bow, signed: positive = we are looking at her starboard side.
                double aob = angleDiff(brg + 180, s.heading);
                double sin = Math.sin(Math.toRadians(aob));
                // What the eye receives: a bearing, an angular height and an angular width.
                // Range and course are NOT in here. The commander has to derive them.
                double apparent = CLASS_LENGTH[s.cls] * Math.max(0.14, Math.abs(sin));
                Json c = new Json();
                c.num("id", s.id).num("bearing", brg).num("cls", s.cls)
                 .num("mast", CLASS_MAST[s.cls] / range * 1000)          // milliradians
                 .num("wid", apparent / range * 1000)
                 .num("dir", sin >= 0 ? 1 : -1)
                 .bool("near", Math.abs(aob) < 90);
                if (training) {
                    c.str("name", s.name).num("range", range).num("heading", s.heading)
                     .num("aob", aob).num("kn", knots(s.speed)).str("ai", s.aiState);
                }
                arr.append(c);
            }
            j.raw("contacts", arr.append(']').toString());
        }
        if (station.equals("ENG") || all) {
            // Hydrophone: loudest ship near the bearing the operator is listening on.
            double best = 0, rpm = 0;
            for (Ship s : ships) {
                if (!s.alive) continue;
                double diff = Math.abs(angleDiff(bearing(subX, subY, s.x, s.y), hydroBearing));
                double focus = Math.pow(Math.max(0, Math.cos(Math.toRadians(diff))), 8);
                double loud = s.loudness / (1 + Math.hypot(s.x - subX, s.y - subY) / 1500) * focus;
                if (loud > best) { best = loud; rpm = rpmOf(s); }
            }
            double selfNoise = 0.05 + 0.1 * speedOrder;
            j.num("hydro", hydroBearing).num("hydroSignal", best / (best + selfNoise)).num("hydroRpm", best > 0.01 ? rpm : 0);
            // Damage board. A real crew gets shouted reports, not percentages, so in realistic
            // mode the figures are rounded to coarse steps before they leave the server.
            StringBuilder h = new StringBuilder(), f = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) { h.append(','); f.append(','); }
                h.append(Math.round(training ? compHealth[i] : Math.floor(compHealth[i] / 25) * 25));
                f.append(Math.round(training ? compFlood[i] : Math.ceil(compFlood[i] / 25) * 25));
            }
            j.str("comps", h.toString()).str("floods", f.toString()).num("repairing", repairing);
        }
        return j.toString();
    }

    /** How loud the nearest escort's ASDIC ping is through the hull. Escorts ping while they listen. */
    double asdicLevel() {
        double best = 0;
        for (Ship s : ships) {
            if (!s.alive || s.cls != 3 || s.speed > 8) continue;       // no pinging during a run-in
            double slant = Math.hypot(Math.hypot(s.x - subX, s.y - subY), depth);
            best = Math.max(best, clamp(1 - slant / 3000, 0, 1));
        }
        return best;
    }

    /** Fast screws passing close overhead: the sound every crew dreaded. */
    double screwsLevel() {
        double best = 0;
        for (Ship s : ships) {
            if (!s.alive || s.cls != 3) continue;
            double slant = Math.hypot(Math.hypot(s.x - subX, s.y - subY), depth);
            best = Math.max(best, clamp(1 - slant / 700, 0, 1) * Math.min(1, s.speed / 12));
        }
        return best;
    }

    // ---- small math helpers ----
    static double norm(double a) { a %= 360; return a < 0 ? a + 360 : a; }
    /** Signed shortest difference a - b in degrees, in -180..180. */
    static double angleDiff(double a, double b) { double d = norm(a - b); return d > 180 ? d - 360 : d; }
    static double bearing(double fx, double fy, double tx, double ty) { return norm(Math.toDegrees(Math.atan2(tx - fx, ty - fy))); }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Tiny JSON builder so we need no external libraries. */
    static class Json {
        private final StringBuilder sb = new StringBuilder("{");
        private void key(String k) { if (sb.length() > 1) sb.append(','); sb.append('"').append(k).append("\":"); }
        Json num(String k, double v) {
            key(k);
            if (v == Math.rint(v)) sb.append((long) v);                    // whole numbers: 20 not 20.00
            else sb.append(String.format(Locale.US, "%.2f", v));
            return this;
        }
        Json str(String k, String v) { key(k); sb.append('"').append(v.replace("\"", "'")).append('"'); return this; }
        Json bool(String k, boolean v) { key(k); sb.append(v); return this; }
        Json raw(String k, String v) { key(k); sb.append(v); return this; }
        @Override public String toString() { return sb + "}"; }
    }
}
