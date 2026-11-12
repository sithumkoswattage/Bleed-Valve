import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One instance (and one thread) per connected player.
 * Reads lines from the player's socket and turns them into game commands.
 *
 * Two threads write to this player's socket: this handler (replies) and the GameLoop (STATE).
 * send() is synchronized so their lines never get mixed together.
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final GameState state;
    private final List<ClientHandler> clients;
    private final PrintWriter out;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Serialises station hand-outs so two players joining at once can never both get KOMM. */
    private static final Object ASSIGN_LOCK = new Object();

    private volatile String station = null;                 // KOMM, ENG, ALL or SPEC once the player says HELLO
    private volatile boolean ready = false;                 // lobby: this player pressed READY
    private volatile long lastSeen = System.currentTimeMillis();

    public ClientHandler(Socket socket, GameState state, List<ClientHandler> clients) throws IOException {
        this.socket = socket;
        this.state = state;
        this.clients = clients;
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    @Override
    public void run() {
        Log.info("Player connected from " + socket.getRemoteSocketAddress());
        send("WELCOME|The Bleed Valve. Send HELLO|KOMM, HELLO|ENG, HELLO|ALL or HELLO|SPEC");
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = in.readLine()) != null) {   // blocks until a line arrives
                lastSeen = System.currentTimeMillis();
                if (!line.isBlank()) handle(line.trim());
            }
        } catch (IOException e) {
            if (!closed.get()) Log.info("Connection error: " + e.getMessage());
        } finally {
            close("disconnected");
        }
    }

    private void handle(String line) {
        String[] p = line.split("\\|");
        switch (p[0].toUpperCase()) {
            case "PING" -> send("PONG");
            case "HELLO" -> {
                String s = p.length > 1 ? p[1].toUpperCase() : "";
                if (!s.equals("KOMM") && !s.equals("ENG") && !s.equals("ALL") && !s.equals("SPEC")) {
                    send("ERROR|Station must be KOMM, ENG, ALL or SPEC");
                    return;
                }
                if (station != null) { send("ERROR|You already have a station"); return; }
                assign(s);
            }
            case "TAKEOVER" -> takeOver();
            case "CMD" -> {
                if (station == null) { send("ERROR|Send HELLO|<station> first"); return; }
                if (station.equals("SPEC")) { send("ERROR|Spectators cannot give orders"); return; }
                if (p.length < 2) { send("ERROR|Use CMD|<ACTION>|<VALUE>"); return; }
                if (p[1].equalsIgnoreCase("READY")) {                // lobby only, handled here not in GameState
                    ready = p.length > 2 && !p[2].equals("0");
                    return;
                }
                double value;
                try {
                    value = p.length > 2 ? Double.parseDouble(p[2]) : 0;
                } catch (NumberFormatException e) {
                    send("ERROR|Value must be a number");
                    return;
                }
                String error = state.applyCommand(station, p[1], value);   // takes the lock inside
                if (error != null) send("ERROR|" + error);
            }
            case "QUIT" -> close("quit");
            default -> send("ERROR|Unknown message " + p[0]);
        }
    }

    /**
     * The server, not the client, decides who sits where.
     *  - KOMM / ENG: you get it if free, otherwise the other free seat, otherwise you watch.
     *  - ALL (solo): only if the boat is empty. If someone is already aboard you take the seat they left.
     *  - A solo ALL player who is still in the lobby is moved to KOMM when a second player joins.
     */
    private void assign(String want) {
        String got;
        boolean inLobby = state.phase().equals("LOBBY");
        synchronized (ASSIGN_LOCK) {
            ClientHandler allHolder = holder("ALL");
            boolean komm = holder("KOMM") != null, eng = holder("ENG") != null;
            if (want.equals("SPEC")) {
                got = "SPEC";
            } else if (allHolder != null) {
                if (inLobby) {                                        // two humans now: split the seats
                    allHolder.station = "KOMM"; allHolder.ready = false;
                    allHolder.send("JOINED|KOMM");
                    got = "ENG";
                } else got = "SPEC";
            } else if (want.equals("ALL")) {
                got = (!komm && !eng) ? "ALL" : !komm ? "KOMM" : !eng ? "ENG" : "SPEC";
            } else if (want.equals("KOMM")) {
                got = !komm ? "KOMM" : !eng ? "ENG" : "SPEC";
            } else {
                got = !eng ? "ENG" : !komm ? "KOMM" : "SPEC";
            }
            station = got;
        }
        ready = false;
        send("JOINED|" + got);
        Log.info("Player asked for " + want + ", seated as " + got);
    }

    /** Take over a seat nobody is manning (a crewmate dropped out), or step out of the spectator seat. */
    private void takeOver() {
        synchronized (ASSIGN_LOCK) {
            if (station == null) return;
            boolean komm = holder("KOMM") != null || holder("ALL") != null;
            boolean eng = holder("ENG") != null || holder("ALL") != null;
            String got;
            switch (station) {
                case "KOMM" -> got = !eng ? "ALL" : null;
                case "ENG" -> got = !komm ? "ALL" : null;
                case "SPEC" -> got = !komm ? "KOMM" : !eng ? "ENG" : null;
                default -> got = null;
            }
            if (got == null) { send("ERROR|Nobody to take over from"); return; }
            station = got;
            send("JOINED|" + got);
            Log.info("Player took over: now " + got);
        }
    }

    private ClientHandler holder(String st) {
        for (ClientHandler c : clients) if (c != this && st.equals(c.station)) return c;
        return null;
    }

    /** Called from this thread AND from the GameLoop thread, so it is synchronized. */
    public synchronized void send(String msg) {
        if (closed.get()) return;
        out.println(msg);
        if (out.checkError()) close("write failed");
    }

    /** Safe to call from any thread, any number of times: only the first call does the work. */
    public void close(String why) {
        if (!closed.compareAndSet(false, true)) return;
        clients.remove(this);
        try { socket.close(); } catch (IOException ignored) { }
        Log.info("Player " + (station == null ? "?" : station) + " left (" + why + ")");
    }

    public String getStation() { return station; }
    public boolean isReady() { return ready; }
    public void setReady(boolean r) { ready = r; }
    public long getLastSeen() { return lastSeen; }
}
