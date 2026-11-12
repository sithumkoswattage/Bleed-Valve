/**
 * The damage-control party, on its own thread.
 *
 * This is a textbook **producer-consumer**: the engineer's client handler thread PRODUCES
 * repair orders into `state.repairQueue`, and this single thread CONSUMES them.
 *
 * The important call is `repairQueue.take()`. It BLOCKS — the thread sleeps using no CPU
 * until an order arrives, and wakes the instant one does. That is the difference between a
 * blocking queue and a polling loop that burns a core asking "is there anything yet?".
 *
 * There is exactly one crew, so only one compartment can be worked at a time. Order a second
 * repair and it waits its turn in the queue. The crew is a contended resource in the same way
 * the battery is, which is why the engineer has to choose what to save first.
 */
public class RepairCrew implements Runnable {
    private static final double RATE = 8.0;      // integrity points per second
    private static final long STEP_MS = 200;

    private final GameState state;

    public RepairCrew(GameState state) {
        this.state = state;
    }

    @Override
    public void run() {
        Log.info("Damage control party standing by");
        while (true) {
            int compartment;
            try {
                compartment = state.repairQueue.take();    // blocks here until an order arrives
            } catch (InterruptedException e) {
                return;
            }
            work(compartment);
        }
    }

    /** Works one compartment until it is sound again, the patrol ends, or the boat is lost. */
    private void work(int c) {
        state.lock.lock();
        try {
            state.repairing = c;
            state.repairProgress = 0;
            state.events.add("EVENT|REPAIR_START|" + GameState.COMPARTMENTS[c]);
        } finally {
            state.lock.unlock();
        }

        boolean finished = false;
        while (!finished) {
            try { Thread.sleep(STEP_MS); } catch (InterruptedException e) { return; }

            state.lock.lock();
            try {
                if (!state.phase.equals("PLAYING")) { finished = true; continue; }

                double dt = STEP_MS / 1000.0;
                state.compHealth[c] = Math.min(100, state.compHealth[c] + RATE * dt);
                // Patching a seam also lets the party bail that compartment out by hand.
                state.compFlood[c] = Math.max(0, state.compFlood[c] - 2.0 * dt);
                state.repairProgress = state.compHealth[c];

                // The party's job is the hole, not the water: once the seam is sound they move on
                // and the bilge pumps clear the rest. Two systems, two jobs, one battery.
                if (state.compHealth[c] >= 100) finished = true;
            } finally {
                state.lock.unlock();
            }
        }

        state.lock.lock();
        try {
            if (state.phase.equals("PLAYING")) {
                state.events.add("EVENT|REPAIR_DONE|" + GameState.COMPARTMENTS[c] + " made good");
            }
            state.repairing = -1;
            state.repairProgress = 0;
        } finally {
            state.lock.unlock();
        }
    }
}
