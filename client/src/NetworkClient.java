import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The client's whole network layer. Pure Java sockets and threads.
 *
 * Threads created here:
 *   Listener  - blocks on readLine(), hands every server message to the UI
 *   Heartbeat - sends PING once a second so the server knows we are alive
 * (The Swing Event Dispatch Thread is the third thread in this program; it owns the UI.)
 *
 * Thread safety: the Listener thread must NEVER touch Swing components directly.
 * It passes messages through `onMessage`, and the UI re-posts them onto the EDT.
 */
public class NetworkClient {
    private final String host;
    private final int port;
    private volatile String station;                // the seat the SERVER gave us (may differ from what we asked for)
    private final Consumer<String> onMessage;   // called on the Listener thread
    private final Consumer<String> onClosed;    // called once when the link drops

    private Socket socket;
    private PrintWriter out;
    private volatile boolean running = false;

    public NetworkClient(String host, int port, String station,
                         Consumer<String> onMessage, Consumer<String> onClosed) {
        this.host = host;
        this.port = port;
        this.station = station;
        this.onMessage = onMessage;
        this.onClosed = onClosed;
    }

    /** Opens the TCP connection and starts both threads. Throws if the server is unreachable. */
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);   // 5 s connect timeout
        socket.setTcpNoDelay(true);                                // don't delay small packets
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        running = true;

        Thread listener = new Thread(this::listenLoop, "Listener");
        listener.setDaemon(true);
        listener.start();

        Thread heartbeat = new Thread(this::heartbeatLoop, "Heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        send("HELLO|" + station);
    }

    /** Runs on the Listener thread: one blocking read after another until the socket dies. */
    private void listenLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = in.readLine()) != null) {
                onMessage.accept(line);
            }
        } catch (IOException e) {
            // expected when the socket is closed
        } finally {
            if (running) {
                running = false;
                onClosed.accept("Connection to the server was lost");
            }
        }
    }

    /** Runs on the Heartbeat thread. */
    private void heartbeatLoop() {
        while (running) {
            send("PING");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * Called from the EDT (button clicks) and from the Heartbeat thread,
     * so it is synchronized to stop two lines being interleaved on the wire.
     */
    public synchronized void send(String msg) {
        if (!running || out == null) return;
        out.println(msg);
    }

    public void command(String action, Object value) {
        if ("SPEC".equals(station)) return;          // spectators watch only
        send("CMD|" + action + "|" + value);
    }

    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    public boolean isRunning() { return running; }
    public String getStation() { return station; }
    public void setStation(String s) { station = s; }
}
