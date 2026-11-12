import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * Stations 3 and 4 merged: Leitstand (depth, trim, helm) and Maschinenraum
 * (engine telegraph, battery, bilge pumps).
 *
 * This is where the shared-resource conflict lives. The motors and the bilge pumps
 * draw from the same battery, and on the server those two drains run from different
 * threads, which is exactly what the lock protects.
 */
public class EngineerPanel extends StationPanel {
    private final Gauge depth = new Gauge("DEPTH", " m", 0, 250);
    private final Gauge heading = new Gauge("HEADING", "", 0, 360, 260);
    // What the switchboard really showed: volts sag as the battery empties, amps show the load.
    private final Meter volts = new Meter("BATTERY VOLTS", "V", 170, 220);
    private final Meter amps = new Meter("AMMETER (left of mark = charging)", "A", -400, 1200);
    private final TrainingStrip trainingStrip = new TrainingStrip();
    private final HydrophoneDial hydro = new HydrophoneDial();
    private final Telegraph telegraph = new Telegraph();
    private final BoatStatus boatStatus = new BoatStatus();
    private final DamageBoard damage;

    private final Set<Integer> held = new HashSet<>();
    private double hydroBearing = 0, lastHydroSent = -999;
    private double orderedDepth = 0, orderedHeading = 45;
    private long lastHydroAt = 0;
    private boolean pumps = false;

    public EngineerPanel(NetworkClient net) {
        super(net);
        damage = new DamageBoard(c -> net.command("REPAIR", c));
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel dials = new JPanel(new GridLayout(1, 2, 10, 10));
        dials.setOpaque(false);
        dials.add(depth);
        dials.add(heading);

        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.setOpaque(false);
        left.add(dials, BorderLayout.NORTH);
        left.add(buildControls(), BorderLayout.CENTER);
        add(left, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setPreferredSize(new Dimension(300, 10));
        right.add(hydro);
        right.add(Box.createVerticalStrut(6));
        right.add(buildHydroControls());
        right.add(Box.createVerticalStrut(10));
        right.add(damage);
        add(right, BorderLayout.EAST);

        JPanel meters = new JPanel(new GridLayout(1, 3, 14, 0));
        meters.setOpaque(false);
        meters.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 4));
        meters.add(volts);
        meters.add(amps);
        meters.add(trainingStrip);
        add(meters, BorderLayout.SOUTH);

        bindKeys();
    }

    private JComponent buildControls() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.smooth((Graphics2D) g.create());
                Theme.plate(g2, 0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        p.add(fixed(telegraph, 64));
        p.add(Box.createVerticalStrut(8));

        String[] names = {"STOP", "SLOW", "HALF", "FULL"};
        JPanel speeds = row(4);
        for (int i = 0; i < 4; i++) {
            int order = i;
            speeds.add(button(names[i], e -> net.command("SPEED", order)));
        }
        p.add(fixed(speeds, 32));
        p.add(Box.createVerticalStrut(10));

        p.add(fixed(label("DEPTH KEEPING"), 16));
        JPanel dep = row(4);
        dep.add(button("SURFACE", e -> setDepth(0)));
        dep.add(button("PERISCOPE 12", e -> setDepth(12)));
        dep.add(button("RISE 10", e -> setDepth(orderedDepth - 10)));
        dep.add(button("DIVE 10", e -> setDepth(orderedDepth + 10)));
        p.add(fixed(dep, 32));
        p.add(Box.createVerticalStrut(10));

        p.add(fixed(label("HELM"), 16));
        JPanel hd = row(4);
        hd.add(button("PORT 30", e -> setHeading(orderedHeading - 30)));
        hd.add(button("PORT 10", e -> setHeading(orderedHeading - 10)));
        hd.add(button("STBD 10", e -> setHeading(orderedHeading + 10)));
        hd.add(button("STBD 30", e -> setHeading(orderedHeading + 30)));
        p.add(fixed(hd, 32));
        p.add(Box.createVerticalStrut(10));

        p.add(fixed(label("BILGE PUMPS   (they share the motors' battery, and they are loud)"), 16));
        JPanel pm = row(1);
        pm.add(button("TOGGLE BILGE PUMPS   (P)", e -> net.command("PUMPS", pumps ? 0 : 1)));
        p.add(fixed(pm, 32));
        p.add(Box.createVerticalStrut(14));
        p.add(boatStatus);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel row(int cols) {
        JPanel r = new JPanel(new GridLayout(1, cols, 6, 0));
        r.setOpaque(false);
        return r;
    }

    /** Pins a component to one height so BoxLayout cannot stretch it. */
    private JComponent fixed(JComponent c, int h) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setPreferredSize(new Dimension(10, h));
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private JComponent buildHydroControls() {
        JPanel p = new JPanel(new GridLayout(1, 2, 6, 0));
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(400, 34));
        p.add(button("< LISTEN  (Q)", e -> hydroBearing = GameMath.norm(hydroBearing - 5)));
        p.add(button("LISTEN >  (E)", e -> hydroBearing = GameMath.norm(hydroBearing + 5)));
        return p;
    }

    private void setDepth(double m) {
        orderedDepth = GameMath.clamp(m, 0, 250);
        net.command("DEPTH", Math.round(orderedDepth));
    }

    private void setHeading(double deg) {
        orderedHeading = GameMath.norm(deg);
        net.command("HEADING", Math.round(orderedHeading));
    }

    private JButton button(String text, java.awt.event.ActionListener a) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setBackground(Theme.STEEL);
        b.setForeground(Theme.INK);
        b.setFont(Theme.LABEL);
        b.setBorder(BorderFactory.createLineBorder(Theme.STEEL_EDGE));
        b.addActionListener(a);
        return b;
    }

    private JComponent label(String text) {
        return new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.smooth((Graphics2D) g.create());
                g2.setFont(Theme.LABEL);
                g2.setColor(Theme.AMBER_DIM);
                g2.drawString(text, 2, getHeight() / 2 + 4);
                g2.dispose();
            }
        };
    }

    private void bindKeys() {
        for (int code : new int[]{KeyEvent.VK_Q, KeyEvent.VK_E}) {
            bind(code, false, () -> held.add(code));
            bind(code, true, () -> held.remove(code));
        }
        bind(KeyEvent.VK_P, false, () -> net.command("PUMPS", pumps ? 0 : 1));
        bind(KeyEvent.VK_T, false, hydro::tap);
    }

    private void bind(int code, boolean onRelease, Runnable action) {
        String name = "eng" + code + (onRelease ? "-up" : "-down");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(code, 0, onRelease), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    @Override
    public void apply(Snapshot s) {
        depth.setValue(s.d("depth"));
        depth.setCaption("ord " + Math.round(s.d("targetDepth")) + " m");
        depth.setAlert(s.d("depth") > 200);
        heading.setValue(s.d("heading"));
        heading.setCaption("ord " + Math.round(s.d("targetHeading")));
        volts.setValue(s.d("volts"));
        amps.setValue(s.d("amps"));
        trainingStrip.set(s);
        pumps = s.b("pumps");
        telegraph.set(s.i("speed"), pumps, s.d("volts") < 186 ? 0 : 100, s.i("maxSpeed"));
        boatStatus.set(s);
        damage.update(s.s("comps"), s.s("floods"), s.i("repairing"), s.b("training"));
        if (lastHydroSent < -900) hydroBearing = s.d("hydro");   // adopt the server's wheel position
        hydro.update(hydroBearing, s.d("hydroSignal"), s.d("hydroRpm"), s.b("training"));
        orderedDepth = s.d("targetDepth");
        orderedHeading = s.d("targetHeading");
    }

    @Override
    public void tick() {
        double turn = 0;
        if (held.contains(KeyEvent.VK_Q)) turn -= 1.6;
        if (held.contains(KeyEvent.VK_E)) turn += 1.6;
        if (turn != 0) hydroBearing = GameMath.norm(hydroBearing + turn);

        long now = System.currentTimeMillis();
        if (Math.abs(GameMath.angleDiff(hydroBearing, lastHydroSent)) > 0.5 && now - lastHydroAt > 100) {
            net.command("HYDRO", Math.round(hydroBearing));
            lastHydroSent = hydroBearing;
            lastHydroAt = now;
        }

        depth.tick(); heading.tick();
        volts.tick(); amps.tick();
        hydro.tick();
        depth.repaint(); heading.repaint();
        volts.repaint(); amps.repaint(); trainingStrip.repaint();
        hydro.repaint(); telegraph.repaint(); boatStatus.repaint(); damage.repaint();
    }

    @Override
    public String title() { return "LEITSTAND / MASCHINENRAUM - Depth, Power & Hydrophone"; }

    /**
     * A plain-text systems board. It shows the consequences of the engineer's orders,
     * which is what makes the shared battery feel like a real trade-off: speed, pumps
     * and depth all spend the same charge.
     */
    private static class BoatStatus extends JComponent {
        /** Submerged speeds in m/s; the diesels give 2.2x this on the surface. */
        private static final double[] MS = {0, 1.5, 3.0, 4.2};
        private Snapshot s;

        void set(Snapshot s) { this.s = s; }

        @Override protected void paintComponent(Graphics g0) {
            if (s == null) return;
            Graphics2D g = Theme.smooth((Graphics2D) g0.create());
            g.setFont(Theme.SMALL);
            g.setColor(Theme.AMBER_DIM);
            g.drawString("CONTROL ROOM", 2, 12);

            int order = Math.max(0, Math.min(3, s.i("speed")));
            boolean submerged = s.d("depth") >= 1;
            double knots = MS[order] * (submerged ? 1.0 : 2.2) * 1.944;
            double a = s.d("amps");
            double asdic = s.d("asdic"), screws = s.d("screws");

            String[][] rows = {
                {"Log",       String.format("%.1f kn  (%s)", knots, submerged ? "electric motors" : "diesels")},
                {"Battery",   a < -1 ? "charging" : a < 1 ? "no load" : a > 700 ? "heavy discharge" : "discharging"},
                {"Periscope", s.b("scopeUp") ? "UP - it can be seen" : submerged ? "down" : "bridge watch posted"},
                {"By ear",    screws > 0.25 ? "FAST SCREWS CLOSE ABOVE"
                            : asdic > 0.6 ? "ASDIC pinging, loud" : asdic > 0.15 ? "ASDIC pinging, distant" : "nothing but the sea"},
            };
            int y = 30;
            for (String[] r : rows) {
                g.setColor(Theme.STEEL_EDGE.brighter());
                g.drawString(r[0], 6, y);
                g.setColor(r[0].equals("By ear") && (screws > 0.25 || asdic > 0.6) ? Theme.ALERT : Theme.INK);
                g.drawString(r[1], 92, y);
                y += 16;
            }
            String warn = null;
            if (s.d("depth") > 200) warn = "BELOW TEST DEPTH - RISE NOW";
            else if (s.d("volts") < 180 && submerged) warn = "BATTERY NEARLY FLAT - SURFACE TO CHARGE";
            if (warn != null) {
                g.setFont(Theme.LABEL);
                g.setColor(Theme.ALERT);
                g.drawString(warn, 6, y + 6);
            }
            g.dispose();
        }
    }

    /** Only drawn in training mode: the numbers no real crew ever had. */
    private static class TrainingStrip extends JComponent {
        private Snapshot s;
        void set(Snapshot s) { this.s = s; }

        @Override protected void paintComponent(Graphics g0) {
            if (s == null) return;
            Graphics2D g = Theme.smooth((Graphics2D) g0.create());
            g.setFont(Theme.SMALL);
            if (!s.b("training")) {
                g.setColor(Theme.STEEL_EDGE);
                g.drawString("REALISTIC - F1 for training aids", 0, 13);
            } else {
                g.setColor(Theme.AMBER_DIM);
                g.drawString("TRAINING AIDS (F1 hides them)", 0, 13);
                g.setColor(Theme.INK);
                g.drawString(String.format("battery %d%%   hull %d%%   flooding %d%%",
                        Math.round(s.d("battery")), Math.round(s.d("hull")), Math.round(s.d("flooding"))), 0, 28);
                g.setColor(s.b("silent") ? Theme.PHOSPHOR : Theme.AMBER);
                g.drawString((s.b("silent") ? "silent running" : "making noise")
                        + (s.b("belowLayer") ? ", below the layer" : ", above the layer"), 0, 42);
            }
            g.dispose();
        }
    }

    /** The engine order telegraph, plus warning lamps. */
    private static class Telegraph extends JComponent {
        private int order;
        private boolean pumps;
        private double battery = 100;
        private int maxOrder = 3;

        Telegraph() { setPreferredSize(new Dimension(300, 56)); }

        void set(int order, boolean pumps, double battery, int maxOrder) {
            this.order = order; this.pumps = pumps; this.battery = battery; this.maxOrder = maxOrder;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.smooth((Graphics2D) g0.create());
            String[] names = {"STOP", "SLOW", "HALF", "FULL"};
            g.setFont(Theme.LABEL);
            g.setColor(Theme.AMBER_DIM);
            g.drawString("ENGINE ORDER", 2, 12);

            int lampW = 190;
            int barW = Math.max(120, getWidth() - lampW - 8);
            int bw = barW / 4;
            for (int i = 0; i < 4; i++) {
                boolean on = i == order;
                boolean locked = i > maxOrder;          // engine room too badly damaged for this order
                g.setColor(on ? Theme.AMBER : new Color(0x1A, 0x20, 0x1C));
                g.fillRoundRect(2 + i * bw, 20, bw - 6, 22, 6, 6);
                g.setColor(on ? Theme.STEEL_DARK : locked ? Theme.ALERT : Theme.STEEL_EDGE);
                int tw = g.getFontMetrics().stringWidth(names[i]);
                g.drawString(names[i], 2 + i * bw + (bw - 6 - tw) / 2, 35);
                if (locked && !on) {                    // struck through
                    g.drawLine(2 + i * bw + 6, 31, 2 + i * bw + bw - 12, 31);
                }
            }
            // lamps sit to the RIGHT of the telegraph, never on top of it
            lamp(g, barW + 14, 24, "PUMPS RUNNING", pumps, Theme.PHOSPHOR);
            lamp(g, barW + 14, 40, "LOW BATTERY", battery < 25, Theme.ALERT);
            g.dispose();
        }

        private void lamp(Graphics2D g, int x, int y, String text, boolean on, Color c) {
            g.setColor(on ? c : new Color(0x20, 0x26, 0x22));
            g.fillOval(x, y - 8, 9, 9);
            g.setColor(on ? c : Theme.STEEL_EDGE);
            g.setFont(Theme.SMALL);
            g.drawString(text, x + 13, y);
        }
    }
}
