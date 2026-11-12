import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * Station 1: Der Kommandant.
 *
 * The attack, the way it was actually done:
 *   1. IDENTIFY the ship against the recognition manual. That gives her masthead height.
 *   2. STADIMETER: measure the angle her mast subtends. Height / angle = range.
 *   3. MARK the bearing and range on the plot. Wait. Mark again. Two marks give course and speed.
 *   4. Set range, speed and angle on the bow into the TORPEDO COMPUTER. It works out the lead.
 *   5. FIRE, start the stopwatch, and listen.
 *
 * None of the true numbers are in this program in realistic mode. The server does not send them.
 */
public class KommandantPanel extends StationPanel {
    private static final double TORPEDO_MS = 20;          // a G7a on its 40-knot setting

    private final PeriscopeView scope = new PeriscopeView();
    private final AttackPlot plot = new AttackPlot();
    private final Set<Integer> held = new HashSet<>();
    private final Random rnd = new Random();

    private double bearing = 0;
    private boolean trained = false;
    private double lastSent = -999;
    private long lastSendAt = 0;

    // what the server tells us
    private int torpedoes = 6;
    private double reload, depth;
    private boolean scopeUp, canSee, training;

    // the commander's own estimates
    private int manualClass = 1;
    private double tdcRange = 2000, tdcSpeed = 6, tdcAob = 90;
    private int tdcSide = 1;                              // +1 = her starboard side toward us
    private String stadimeterNote = "no reading yet";
    private long firedAt = 0;
    private double expectedRun = 0;

    public KommandantPanel(NetworkClient net) {
        super(net);
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(scope, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setPreferredSize(new Dimension(300, 10));
        right.add(new Manual());
        right.add(Box.createVerticalStrut(6));
        right.add(buildTdc());
        right.add(Box.createVerticalStrut(6));
        right.add(plot);
        right.add(Box.createVerticalStrut(6));
        right.add(new Tubes());
        add(right, BorderLayout.EAST);
        bindKeys();
    }

    // ------------------------------------------------------------------ torpedo computer

    /** Lead angle in degrees: how far ahead of the target the torpedo must be aimed. */
    private double lead() {
        double ratio = (tdcSpeed / 1.944) / TORPEDO_MS * Math.sin(Math.toRadians(tdcAob));
        return Math.toDegrees(Math.asin(GameMath.clamp(ratio, -1, 1)));
    }

    private double torpedoCourse() { return GameMath.norm(bearing + tdcSide * lead()); }

    /** Seconds the torpedo should run, from the sine rule on the firing triangle. */
    private double runTime() {
        double a = Math.toRadians(tdcAob), l = Math.toRadians(lead());
        double s = Math.sin(a + l);
        double run = s < 0.05 ? tdcRange : tdcRange * Math.sin(a) / s;
        if (tdcAob < 3 || tdcAob > 177) run = tdcRange;
        return run / TORPEDO_MS;
    }

    private JComponent buildTdc() {
        JPanel p = plate();
        p.add(fixed(label("TORPEDO COMPUTER"), 15));
        p.add(fixed(adjust(() -> String.format("RANGE   %,d m", Math.round(tdcRange)),
                () -> tdcRange = Math.max(200, tdcRange - 100), () -> tdcRange = Math.min(8000, tdcRange + 100)), 24));
        p.add(fixed(adjust(() -> String.format("SPEED   %.1f kn", tdcSpeed),
                () -> tdcSpeed = Math.max(0, tdcSpeed - 0.5), () -> tdcSpeed = Math.min(30, tdcSpeed + 0.5)), 24));
        p.add(fixed(adjust(() -> String.format("AOB     %d° %s", Math.round(tdcAob), tdcSide > 0 ? "STBD" : "PORT"),
                () -> tdcAob = Math.max(0, tdcAob - 5), () -> tdcAob = Math.min(180, tdcAob + 5)), 24));
        p.add(Box.createVerticalStrut(4));

        JPanel row = new JPanel(new GridLayout(1, 4, 4, 0));
        row.setOpaque(false);
        row.add(button("RANGE (R)", e -> stadimeter()));
        row.add(button("MARK (X)", e -> mark()));
        row.add(button("PLOT>TDC", e -> usePlot()));
        row.add(button("SIDE", e -> tdcSide = -tdcSide));
        p.add(fixed(row, 26));

        p.add(fixed(new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.smooth((Graphics2D) g.create());
                g2.setFont(Theme.SMALL);
                g2.setColor(Theme.AMBER_DIM);
                g2.drawString(stadimeterNote, 2, 13);
                g2.setFont(Theme.LABEL);
                g2.setColor(Theme.PHOSPHOR);
                double l = lead();
                g2.drawString(String.format("LEAD %.1f° %s    TORPEDO COURSE %03d", l,
                        tdcSide > 0 ? "right" : "left", Math.round(torpedoCourse()) % 360), 2, 29);
                long running = firedAt == 0 ? -1 : (System.currentTimeMillis() - firedAt) / 1000;
                if (running >= 0 && running < expectedRun + 25) {
                    g2.setColor(running > expectedRun + 6 ? Theme.ALERT : Theme.AMBER);
                    g2.drawString(String.format("STOPWATCH %d s   expected %d s", running, Math.round(expectedRun)), 2, 44);
                } else {
                    g2.setColor(Theme.INK);
                    g2.drawString(String.format("RUN TIME %d s", Math.round(runTime())), 2, 44);
                }
                g2.dispose();
            }
        }, 48));
        return p;
    }

    /** Height / angle = range. Wrong ship in the manual means wrong height means wrong range. */
    private void stadimeter() {
        Snapshot.Contact c = scope.centred();
        if (!canSee) { stadimeterNote = "cannot see: raise the periscope (W)"; return; }
        if (c == null) { stadimeterNote = "no ship on the wire: centre one first"; return; }
        double angle = c.mast * (1 + rnd.nextGaussian() * 0.04);     // a hand on a brass wheel, in a swell
        tdcRange = Math.round(ShipClass.MAST[manualClass] / (angle / 1000.0) / 10) * 10;
        tdcRange = GameMath.clamp(tdcRange, 200, 8000);
        stadimeterNote = String.format("%.1f mrad, mast %.0f m -> %,d m", angle,
                ShipClass.MAST[manualClass], Math.round(tdcRange));
    }

    private void mark() {
        if (!canSee) { stadimeterNote = "cannot see: nothing to mark"; return; }
        plot.mark(bearing, tdcRange);
    }

    /** The navigator reads course and speed off the plot; the computer turns course into angle on the bow. */
    private void usePlot() {
        double[] sol = plot.solution();
        if (sol == null) { stadimeterNote = "plot needs two marks, some seconds apart"; return; }
        double aob = GameMath.angleDiff(bearing + 180, sol[0]);
        tdcSide = aob >= 0 ? 1 : -1;
        tdcAob = Math.round(Math.abs(aob));
        tdcSpeed = Math.round(sol[1] * 2) / 2.0;
        stadimeterNote = String.format("from plot: course %03d, %.1f kn", Math.round(sol[0]) % 360, sol[1]);
    }

    private void fire() {
        expectedRun = runTime();
        firedAt = System.currentTimeMillis();
        net.command("FIRE", Math.round(torpedoCourse()));
    }

    // ------------------------------------------------------------------ small UI builders

    private JPanel plate() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.smooth((Graphics2D) g.create());
                Theme.plate(g2, 0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return p;
    }

    /** A dial you nudge: a painted value with - and + beside it. */
    private JComponent adjust(Supplier<String> value, Runnable minus, Runnable plus) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.smooth((Graphics2D) g.create());
                g2.setFont(Theme.LABEL);
                g2.setColor(Theme.INK);
                g2.drawString(value.get(), 2, getHeight() / 2 + 4);
                g2.dispose();
            }
        }, BorderLayout.CENTER);
        JPanel b = new JPanel(new GridLayout(1, 2, 4, 0));
        b.setOpaque(false);
        b.setPreferredSize(new Dimension(84, 10));
        b.add(button("-", e -> minus.run()));
        b.add(button("+", e -> plus.run()));
        row.add(b, BorderLayout.EAST);
        return row;
    }

    private JButton button(String text, java.awt.event.ActionListener a) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setBackground(Theme.STEEL);
        b.setForeground(Theme.INK);
        b.setFont(Theme.SMALL);
        b.setMargin(new java.awt.Insets(0, 0, 0, 0));
        b.setBorder(BorderFactory.createLineBorder(Theme.STEEL_EDGE));
        b.addActionListener(a);
        return b;
    }

    private JComponent label(String text) {
        return new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.smooth((Graphics2D) g.create());
                g2.setFont(Theme.SMALL);
                g2.setColor(Theme.AMBER_DIM);
                g2.drawString(text, 2, 11);
                g2.dispose();
            }
        };
    }

    private JComponent fixed(JComponent c, int h) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setPreferredSize(new Dimension(10, h));
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private void bindKeys() {
        for (int code : new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_A, KeyEvent.VK_D}) {
            bind(code, false, () -> held.add(code));
            bind(code, true, () -> held.remove(code));
        }
        bind(KeyEvent.VK_SPACE, false, this::fire);
        bind(KeyEvent.VK_W, false, () -> net.command("SCOPE", 1));
        bind(KeyEvent.VK_S, false, () -> net.command("SCOPE", 0));
        bind(KeyEvent.VK_Z, false, scope::toggleZoom);
        bind(KeyEvent.VK_R, false, this::stadimeter);
        bind(KeyEvent.VK_X, false, this::mark);
        bind(KeyEvent.VK_C, false, plot::clear);
    }

    private void bind(int code, boolean onRelease, Runnable action) {
        String name = code + (onRelease ? "-up" : "-down");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(code, 0, onRelease), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    @Override
    public void apply(Snapshot s) {
        if (!trained) bearing = s.d("periscope");
        torpedoes = s.i("torpedoes");
        reload = s.d("reload");
        depth = s.d("depth");
        scopeUp = s.b("scopeUp");
        canSee = s.b("periscopeUp");
        training = s.b("training");
        scope.update(bearing, s.d("heading"), canSee, scopeUp, depth, training, s.contacts);
        plot.update(s.d("ownX"), s.d("ownY"), s.d("heading"), bearing, training, s.contacts);
    }

    @Override
    public void tick() {
        double rate = 1.6;
        double turn = 0;
        if (held.contains(KeyEvent.VK_LEFT) || held.contains(KeyEvent.VK_A)) turn -= rate;
        if (held.contains(KeyEvent.VK_RIGHT) || held.contains(KeyEvent.VK_D)) turn += rate;
        if (turn != 0) { bearing = GameMath.norm(bearing + turn * 0.5); trained = true; }

        long now = System.currentTimeMillis();
        if (Math.abs(GameMath.angleDiff(bearing, lastSent)) > 0.5 && now - lastSendAt > 100) {
            net.command("PERISCOPE", Math.round(bearing));
            lastSent = bearing;
            lastSendAt = now;
        }
        scope.tick();
        repaint();
    }

    @Override
    public String title() { return "DER KOMMANDANT - Periscope & Torpedo Computer"; }

    /** The recognition manual: click the entry that matches what you see. */
    private class Manual extends JComponent {
        Manual() {
            setPreferredSize(new Dimension(280, 150));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
            setAlignmentX(LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int row = (e.getY() - 20) / 32;
                    if (row >= 0 && row < 4) manualClass = row;
                }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.smooth((Graphics2D) g0.create());
            Theme.plate(g, 0, 0, getWidth() - 1, getHeight() - 1);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.AMBER_DIM);
            g.drawString("RECOGNITION MANUAL   (click the match)", 10, 14);
            for (int i = 0; i < 4; i++) {
                int y = 20 + i * 32;
                boolean sel = i == manualClass;
                if (sel) {
                    g.setColor(new Color(0xFF, 0xB6, 0x42, 34));
                    g.fillRect(4, y, getWidth() - 9, 30);
                }
                double len = ShipClass.LENGTH[i] * 0.42, mast = ShipClass.MAST[i] * 0.72;
                ShipClass.draw(g, i, 40, y + 27, len, mast, 1, sel ? Theme.AMBER : Theme.INK);
                g.setFont(Theme.SMALL);
                g.setColor(sel ? Theme.AMBER : Theme.INK);
                g.drawString(ShipClass.NAME[i], 78, y + 12);
                g.setColor(Theme.STEEL_EDGE.brighter());
                g.drawString(String.format("mast %.0f m, %.0f m, %.0f rpm/kn",
                        ShipClass.MAST[i], ShipClass.LENGTH[i], ShipClass.RPM_PER_KNOT[i]), 78, y + 25);
            }
            g.dispose();
        }
    }

    /** Tube status and the firing lever. */
    private class Tubes extends JComponent {
        Tubes() {
            setPreferredSize(new Dimension(280, 86));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));
            setAlignmentX(LEFT_ALIGNMENT);
            setLayout(null);
            JButton fire = button("FIRE (SPACE)", e -> fire());
            fire.setForeground(Theme.ALERT);
            fire.setFont(Theme.LABEL);
            fire.setBounds(150, 30, 138, 44);
            add(fire);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.smooth((Graphics2D) g0.create());
            Theme.plate(g, 0, 0, getWidth() - 1, getHeight() - 1);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.AMBER_DIM);
            g.drawString("TUBES   W/S scope  Z power  C clear plot", 10, 15);
            for (int i = 0; i < 6; i++) {
                g.setColor(i < torpedoes ? Theme.AMBER : new Color(0x20, 0x26, 0x22));
                g.fillRoundRect(10 + (i % 3) * 44, 30 + (i / 3) * 18, 38, 11, 6, 6);
                g.setColor(Theme.STEEL_EDGE);
                g.drawRoundRect(10 + (i % 3) * 44, 30 + (i / 3) * 18, 38, 11, 6, 6);
            }
            boolean ok = depth <= 20 && reload <= 0 && torpedoes > 0;
            g.setColor(ok ? Theme.PHOSPHOR : Theme.ALERT);
            g.drawString(ok ? "TUBE READY" : reload > 0 ? "LOADING " + (int) Math.ceil(reload) + " s"
                    : torpedoes == 0 ? "NO TORPEDOES" : "TOO DEEP", 10, 78);
            g.dispose();
        }
    }
}
