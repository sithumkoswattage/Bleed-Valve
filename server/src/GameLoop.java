import java.util.List;

/**
 * Runs the simulation at a fixed 20 Hz and broadcasts the result.
 *
 * Pattern: take the lock -> update the world and build the snapshots -> release the lock
 * -> THEN do the slow network sending. Never hold the lock while writing to sockets,
 * or one slow player would freeze every other thread.
 */
public class GameLoop implements Runnable {
    private static final int HZ = 20;
    private static final double DT = 1.0 / HZ;

    private final GameState state;
    private final List<ClientHandler> clients;
    private int crewTick = 0;
    private String lastPhase = "LOBBY";

    public GameLoop(GameState state, List<ClientHandler> clients) {
        this.state = state;
        this.clients = clients;
    }

    /**
     * Lobby rules (called with the lock held): when the phase has just returned to LOBBY everyone's
     * READY is cleared; the mission starts once both seats are manned and every crew member is ready.
     */
    private void lobbyTick() {
        String phase = state.phase;
        if (phase.equals("LOBBY") && !lastPhase.equals("LOBBY")) for (ClientHandler c : clients) c.setReady(false);
        lastPhase = phase;
        if (!phase.equals("LOBBY")) return;
        boolean komm = false, eng = false, allReady = true;
        for (ClientHandler c : clients) {
            String s = c.getStation();
            if (s == null || s.equals("SPEC")) continue;
            if (s.equals("KOMM") || s.equals("ALL")) komm = true;
            if (s.equals("ENG") || s.equals("ALL")) eng = true;
            if (!c.isReady()) allReady = false;
        }
        if (komm && eng && allReady) {
            Log.info("All crew ready - patrol begins");
            state.start();
        }
    }

    /** CREW|k|e|spectators  where k/e are 0 = empty, 1 = manned, 2 = manned and ready. */
    private void broadcastCrew() {
        int k = 0, e = 0, spec = 0;
        for (ClientHandler c : clients) {
            String s = c.getStation();
            if (s == null) continue;
            int v = c.isReady() ? 2 : 1;
            switch (s) {
                case "KOMM" -> k = Math.max(k, v);
                case "ENG" -> e = Math.max(e, v);
                case "ALL" -> { k = Math.max(k, v); e = Math.max(e, v); }
                default -> spec++;
            }
        }
        String msg = "CREW|" + k + "|" + e + "|" + spec;
        for (ClientHandler c : clients) if (c.getStation() != null) c.send(msg);
    }

    @Override
    public void run() {
        Log.info("Game loop running at " + HZ + " Hz");
        long periodNs = 1_000_000_000L / HZ;
        while (true) {
            long start = System.nanoTime();

            String komm, eng, all;
            boolean training;
            state.lock.lock();
            try {
                lobbyTick();
                state.step(DT);
                training = state.training;
                komm = state.snapshot("KOMM");   // each station only gets what it should know
                eng = state.snapshot("ENG");
                all = state.snapshot("ALL");
            } finally {
                state.lock.unlock();
            }

            // Events are produced by other threads into a thread-safe queue; we consume them here.
            String event;
            while ((event = state.events.poll()) != null) {
                // Selective disclosure: in realistic mode some events are never sent at all.
                String name = event.split("\\|", 3)[1];
                if (!training && GameState.TRAINING_ONLY.contains(name)) continue;
                for (ClientHandler c : clients) if (c.getStation() != null) c.send(event);
            }

            if (++crewTick >= (lastPhase.equals("LOBBY") ? 5 : 20)) { crewTick = 0; broadcastCrew(); }

            for (ClientHandler c : clients) {
                String s = c.getStation();
                if (s == null) continue;
                c.send("STATE|" + (s.equals("KOMM") ? komm : s.equals("ENG") ? eng : all));
            }

            long sleepNs = periodNs - (System.nanoTime() - start);
            if (sleepNs > 0) {
                try { Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000)); }
                catch (InterruptedException e) { return; }
            }
        }
    }
}
