import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Text client for testing the server without any graphics.
 *
 *   java TestClient [host] [port] [station]      e.g.  java TestClient 127.0.0.1 5050 ALL
 *
 * Type short commands:  fire 45 | periscope 90 | speed 2 | depth 40 | heading 270 | pumps 1 | hydro 120 | restart | quit
 *
 * It uses 3 threads, just like the real game client will:
 *   main      -> reads your keyboard and sends commands
 *   Listener  -> prints what the server sends (STATE only once per second, events always)
 *   Heartbeat -> sends PING every second so the server knows we're alive
 */
public class TestClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
        String station = args.length > 2 ? args[2].toUpperCase() : "ALL";

        Socket socket = new Socket(host, port);
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Object sendLock = new Object();   // main and Heartbeat both write to `out`

        Thread listener = new Thread(() -> {
            try {
                String line;
                long lastState = 0;
                while ((line = in.readLine()) != null) {
                    if (line.equals("PONG")) continue;
                    if (line.startsWith("STATE|")) {
                        if (System.currentTimeMillis() - lastState < 1000) continue;
                        lastState = System.currentTimeMillis();
                    }
                    System.out.println("<< " + line);
                }
            } catch (Exception ignored) { }
            System.out.println("Disconnected from server.");
            System.exit(0);
        }, "Listener");
        listener.setDaemon(true);
        listener.start();

        Thread heartbeat = new Thread(() -> {
            while (true) {
                synchronized (sendLock) { out.println("PING"); }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "Heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        synchronized (sendLock) { out.println("HELLO|" + station); }

        Scanner keyboard = new Scanner(System.in);
        while (keyboard.hasNextLine()) {
            String[] w = keyboard.nextLine().trim().split("\\s+");
            if (w[0].isEmpty()) continue;
            String msg;
            if (w[0].equalsIgnoreCase("quit")) msg = "QUIT";
            else msg = "CMD|" + w[0].toUpperCase() + "|" + (w.length > 1 ? w[1] : "0");
            synchronized (sendLock) { out.println(msg); }
            if (msg.equals("QUIT")) break;
        }
        socket.close();
    }
}
