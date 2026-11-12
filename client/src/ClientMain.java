import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * THE BLEED VALVE - station client.
 *
 * Threading model (this is the part worth explaining in the viva):
 *   Listener thread  - receives server lines, NEVER touches Swing
 *   Heartbeat thread - sends PING every second
 *   Swing EDT        - owns every pixel and every component
 *
 * The Listener hands work to the EDT with SwingUtilities.invokeLater(). Swing is not
 * thread-safe, so updating a label straight from the Listener thread would be exactly
 * the same class of bug as two server threads writing one variable without a lock.
 */
public class ClientMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(ClientMain::showConnectDialog);
    }

    private static void showConnectDialog() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }

        JTextField host = new JTextField("127.0.0.1");
        JTextField port = new JTextField("5050");
        JComboBox<String> station = new JComboBox<>(new String[]{
                "KOMM  -  Kommandant (periscope & torpedoes)",
                "ENG   -  Leitstand / Maschinenraum",
                "ALL   -  both stations (solo / testing)",
                "SPEC  -  spectator (watch only)"});

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Server address"));
        form.add(host);
        form.add(new JLabel("Port"));
        form.add(port);
        form.add(new JLabel("Your station"));
        form.add(station);

        int ok = JOptionPane.showConfirmDialog(null, form, "The Bleed Valve - report aboard",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) System.exit(0);

        String code = ((String) station.getSelectedItem()).split("\\s")[0];
        try {
            new GameWindow(host.getText().trim(), Integer.parseInt(port.getText().trim()), code);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "Could not reach the server:\n" + e.getMessage() +
                    "\n\nIs Server.java running, and is the address right?",
                    "No connection", JOptionPane.ERROR_MESSAGE);
            showConnectDialog();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Port must be a number.", "Bad port", JOptionPane.ERROR_MESSAGE);
            showConnectDialog();
        }
    }

    /** The main window: one or two station panels, a crew log and a status strip. */
    static class GameWindow extends JFrame {
        private final List<StationPanel> panels = new ArrayList<>();
        private final EventLog log = new EventLog();
        private final StatusBar status = new StatusBar();
        private final NetworkClient net;
        private final Overlay overlay;
        private JComponent centre;
        private String prevPhase = "";
        private int crewK, crewE;
        private final SoundEngine sound = new SoundEngine();
        private boolean training = true;

        GameWindow(String host, int port, String stationCode) throws IOException {
            super("The Bleed Valve");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1180, 780);
            setLocationRelativeTo(null);
            getContentPane().setBackground(Theme.STEEL_DARK);

            net = new NetworkClient(host, port, stationCode,
                    // Both callbacks arrive on the Listener thread, so both hop to the EDT.
                    line -> SwingUtilities.invokeLater(() -> onMessage(line)),
                    why -> SwingUtilities.invokeLater(() -> {
                        status.setConnected(false);
                        log.add(why, Theme.ALERT);
                    }));

            overlay = new Overlay(net);
            setGlassPane(overlay);
            centre = buildStation(stationCode);

            JPanel side = new JPanel(new BorderLayout());
            side.setOpaque(false);
            side.setPreferredSize(new Dimension(300, 10));
            side.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 10));
            side.add(log, BorderLayout.CENTER);

            add(side, BorderLayout.EAST);
            add(status, BorderLayout.SOUTH);

            Thread audio = new Thread(sound, "Sound");
            audio.setDaemon(true);
            audio.start();

            // M mutes. Registered on the window so it works whichever station has focus.
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(javax.swing.KeyStroke.getKeyStroke('M'), "mute");
            getRootPane().getActionMap().put("mute", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    sound.toggleMute();
                    log.add(sound.isMuted() ? "Sound off" : "Sound on", Theme.AMBER_DIM);
                }
            });

            // F1 switches the SERVER between training and realistic. It is the server that stops
            // sending the true picture, so every connected station changes together.
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0), "training");
            getRootPane().getActionMap().put("training", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    net.command("TRAINING", training ? 0 : 1);
                }
            });

            net.connect();
            status.setConnected(true);
            log.add("Connected to " + host + ":" + port, Theme.PHOSPHOR);
            log.add("M mutes.  F1 toggles training aids.", Theme.AMBER_DIM);

            // One repaint timer drives every animation, on the EDT, 30 times a second.
            new Timer(33, e -> {
                for (StationPanel p : panels) p.tick();
                overlay.tick();
                log.repaint();
                status.repaint();
            }).start();

            setVisible(true);
        }

        /** (Re)builds the station screen(s) for a seat. Called at start and whenever the server moves us. */
        private JComponent buildStation(String code) {
            if (centre != null) remove(centre);
            panels.clear();
            JComponent c;
            if (code.equals("ALL") || code.equals("SPEC")) {
                KommandantPanel k = new KommandantPanel(net);
                EngineerPanel e = new EngineerPanel(net);
                panels.add(k);
                panels.add(e);
                JTabbedPane tabs = new JTabbedPane();
                tabs.addTab("PERISCOPE", k);
                tabs.addTab("ENGINE ROOM", e);
                c = tabs;
                setTitle("The Bleed Valve  -  " + (code.equals("SPEC") ? "SPECTATOR" : "both stations"));
            } else {
                StationPanel p = code.equals("KOMM") ? new KommandantPanel(net) : new EngineerPanel(net);
                panels.add(p);
                c = p;
                setTitle("The Bleed Valve  -  " + p.title());
            }
            add(c, BorderLayout.CENTER);
            revalidate();
            repaint();
            return c;
        }

        private void onMessage(String line) {
            if (line.startsWith("STATE|")) {
                Snapshot s = Snapshot.parse(line.substring(6));
                for (StationPanel p : panels) p.apply(s);
                String phase = s.s("phase");
                status.setPhase(phase, s.s("reason"), s.i("tonnage"));
                overlay.onState(s, prevPhase);
                if (!phase.equals(prevPhase)) {
                    // Music only for a patrol that ENDS while we watch, never for a late joiner.
                    if (prevPhase.equals("PLAYING") && phase.equals("WON")) Music.play("win", sound.isMuted());
                    else if (prevPhase.equals("PLAYING") && phase.equals("LOST")) Music.play("lose", sound.isMuted());
                    else Music.stop();
                    prevPhase = phase;
                }
                // Feed the live audio parameters. Values the station doesn't receive stay at 0.
                sound.setEngine(s.i("speed"), s.d("depth") < 1);
                sound.setDepth(s.d("depth"));
                sound.setThreat(s.d("asdic"), s.d("screws"));
                training = s.b("training");
                if (s.values.containsKey("hydroRpm")) sound.setHydrophone(s.d("hydroRpm"), s.d("hydroSignal"));
                if (!s.s("phase").equals("PLAYING")) sound.setAlarm(false);
            } else if (line.startsWith("EVENT|")) {
                String[] parts = line.split("\\|");
                String name = parts.length > 1 ? parts[1] : "";
                String detail = parts.length > 2 ? parts[2] : "";
                String extra = parts.length > 3 ? parts[3] : "";
                log.add(pretty(name, detail, extra), colourFor(name));
                playFor(name);
            } else if (line.startsWith("ERROR|")) {
                log.add("! " + line.substring(6), Theme.ALERT);
            } else if (line.startsWith("CREW|")) {
                String[] c = line.split("\\|");
                crewK = Integer.parseInt(c[1]);
                crewE = Integer.parseInt(c[2]);
                overlay.setCrew(crewK, crewE, Integer.parseInt(c[3]));
                status.updateTakeover();
                status.repaint();
            } else if (line.startsWith("JOINED|")) {
                String seat = line.substring(7);
                log.add(seat.equals("SPEC") ? "You are watching" : "Station " + seat + " manned", Theme.PHOSPHOR);
                if (!seat.equals(net.getStation()) || panels.isEmpty()) {
                    net.setStation(seat);
                    centre = buildStation(seat);
                }
                status.updateTakeover();
                status.repaint();
            } else if (line.startsWith("WELCOME|")) {
                log.add("Server ready", Theme.AMBER_DIM);
            }
        }

        /** Turns a game event into a noise, and raises or clears the klaxon. */
        private void playFor(String name) {
            switch (name) {
                case "TORPEDO_FIRED" -> sound.play("FIRE");
                case "DETONATION", "DEPTH_CHARGE_HIT", "DEPTH_CHARGE_NEAR" -> sound.play("EXPLOSION");
                case "CHARGES_DROPPED" -> sound.play("SPLASH");
                case "HULL_REPORT" -> sound.play("CREAK");
                case "REPAIR_START", "REPAIR_DONE" -> sound.play("CLANG");
                case "DESTROYER_LOST_CONTACT", "CHARGES_EXHAUSTED", "RESET", "GAME_OVER" -> sound.setAlarm(false);
                default -> { }
            }
        }

        private String pretty(String name, String detail, String extra) {
            return switch (name) {
                case "TORPEDO_FIRED" -> "Torpedo away, bearing " + detail;
                case "TORPEDO_HIT"   -> "HIT! " + detail;
                case "TORPEDO_MISS"  -> "Torpedo lost";
                case "CHARGES_DROPPED" -> detail.isEmpty() ? "Splashes overhead - brace!" : detail;
                case "DEPTH_CHARGE_HIT" -> training ? "Depth charge! -" + detail + " in the " + extra
                                                    : "Depth charge! Damage in the " + extra;
                case "DEPTH_CHARGE_NEAR" -> "Depth charge, " + detail;
                case "DETONATION" -> "Detonation after " + detail + " s. Breaking-up noises.";
                case "HULL_REPORT", "MODE" -> detail;
                case "DESTROYER_HUNTING" -> detail;
                case "ATTACK_RUN" -> detail;
                case "DESTROYER_LOST_CONTACT" -> detail;
                case "CHARGES_EXHAUSTED" -> detail;
                case "REINFORCEMENT" -> detail;
                case "REPAIR_ORDERED" -> "Damage party ordered to the " + detail;
                case "REPAIR_START" -> "Party working in the " + detail;
                case "REPAIR_DONE" -> detail;
                case "PUMPS_FAILED" -> "Pumps dead: " + detail;
                case "GAME_OVER" -> detail;
                case "RESET" -> "New patrol";
                default -> name + " " + detail;
            };
        }

        private Color colourFor(String name) {
            if (name.equals("DETONATION")) return Theme.PHOSPHOR;
            if (name.startsWith("DEPTH_CHARGE") || name.equals("CHARGES_DROPPED") || name.equals("ATTACK_RUN")
                    || name.equals("HULL_REPORT")
                    || name.equals("DESTROYER_HUNTING") || name.equals("PUMPS_FAILED")
                    || name.equals("REINFORCEMENT")) return Theme.ALERT;
            if (name.equals("TORPEDO_HIT") || name.equals("REPAIR_DONE")
                    || name.equals("DESTROYER_LOST_CONTACT") || name.equals("CHARGES_EXHAUSTED")) return Theme.PHOSPHOR;
            return Theme.AMBER;
        }

        /** Bottom strip: link state, tonnage, and the restart button when a patrol ends. */
        class StatusBar extends JPanel {
            private boolean connected;
            private String phase = "PLAYING", reason = "";
            private int tonnage;
            private final JButton takeover = new JButton("TAKE OVER EMPTY SEAT");

            StatusBar() {
                setLayout(new BorderLayout());
                setOpaque(false);
                setPreferredSize(new Dimension(10, 34));
                setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
                takeover.setVisible(false);
                takeover.setFocusable(false);
                takeover.setFont(Theme.LABEL);
                takeover.addActionListener(e -> net.send("TAKEOVER"));
                add(takeover, BorderLayout.EAST);
            }

            void setConnected(boolean c) { connected = c; }

            /** Offer to man the other seat when its player has dropped out (or to leave the spectator seat). */
            void updateTakeover() {
                String st = net.getStation();
                boolean other = phase.equals("PLAYING") && (
                        (st.equals("KOMM") && crewE == 0) || (st.equals("ENG") && crewK == 0)
                        || (st.equals("SPEC") && (crewK == 0 || crewE == 0)));
                takeover.setVisible(other);
            }

            void setPhase(String phase, String reason, int tonnage) {
                this.phase = phase; this.reason = reason; this.tonnage = tonnage;
                updateTakeover();
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = Theme.smooth((Graphics2D) g0.create());
                g.setFont(Theme.LABEL);
                g.setColor(connected ? Theme.PHOSPHOR : Theme.ALERT);
                g.fillOval(0, 12, 8, 8);
                g.drawString(connected ? "LINK UP" : "LINK LOST", 14, 20);

                g.setColor(Theme.INK);
                g.drawString("station " + net.getStation(), 110, 20);
                if (phase.equals("PLAYING")) {
                    g.setColor(training ? Theme.AMBER : Theme.PHOSPHOR);
                    g.drawString(training ? "TRAINING AIDS ON (F1)" : "REALISTIC 1943 (F1)", 380, 20);
                }
                g.setColor(Theme.INK);
                g.drawString(String.format("%,d tons sunk", tonnage), 220, 20);

                if (!phase.equals("PLAYING")) {
                    g.setFont(Theme.VALUE);
                    g.setColor(phase.equals("WON") ? Theme.PHOSPHOR : Theme.ALERT);
                    g.drawString(phase + " - " + reason, 360, 22);
                }
                g.dispose();
            }
        }
    }
}
