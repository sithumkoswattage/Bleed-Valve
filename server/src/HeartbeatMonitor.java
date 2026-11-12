import java.util.List;

/**
 * Clients send PING every second. If a player goes silent for TIMEOUT_MS
 * (cable pulled, laptop lid closed, game crashed) we close their connection,
 * so a dead player never blocks or confuses the rest of the crew.
 */
public class HeartbeatMonitor implements Runnable {
    private static final long TIMEOUT_MS = 6000;
    private final List<ClientHandler> clients;

    public HeartbeatMonitor(List<ClientHandler> clients) {
        this.clients = clients;
    }

    @Override
    public void run() {
        while (true) {
            long now = System.currentTimeMillis();
            for (ClientHandler c : clients) {
                if (now - c.getLastSeen() > TIMEOUT_MS) c.close("heartbeat timeout");
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }
}
