import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * THE BLEED VALVE - game server entry point.
 *
 * Thread map (what runs in parallel):
 *   main thread       -> accept() loop: waits for players, spawns a ClientHandler thread per player
 *   Client-N threads  -> one per connected player: reads that player's commands
 *   GameLoop thread   -> 20 Hz: moves everything, broadcasts STATE to every player
 *   EnemyAI thread    -> 5 Hz: the escort "brains" (patrol / search / attack run)
 *   RepairCrew thread -> blocks on a queue of repair orders, works them one at a time
 *   Heartbeat thread  -> 1 Hz: kicks players that stopped sending PING
 *
 * All of them share ONE GameState object, protected by GameState.lock.
 */
public class Server {
    public static final int DEFAULT_PORT = 5050;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        GameState state = new GameState();
        // Thread-safe list: the accept loop adds, handlers remove, GameLoop iterates - all at once.
        List<ClientHandler> clients = new CopyOnWriteArrayList<>();

        new Thread(new GameLoop(state, clients), "GameLoop").start();
        new Thread(new EnemyAI(state), "EnemyAI").start();
        new Thread(new RepairCrew(state), "RepairCrew").start();
        new Thread(new HeartbeatMonitor(clients), "Heartbeat").start();

        try (ServerSocket server = new ServerSocket(port)) {
            Log.info("The Bleed Valve server listening on port " + port);
            int count = 0;
            while (true) {
                Socket socket = server.accept();          // blocks until a player connects
                socket.setTcpNoDelay(true);               // send small packets immediately (low latency)
                ClientHandler handler = new ClientHandler(socket, state, clients);
                clients.add(handler);
                new Thread(handler, "Client-" + (++count)).start();
            }
        }
    }
}
