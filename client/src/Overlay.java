import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * The window's glass pane. It covers the whole game and shows one of three things:
 *   NONE   - invisible, the game is running
 *   LOBBY  - the pre-mission screen: crew, difficulty, mode, READY
 *   END    - the death / victory animation, then the debrief
 *
 * Everything here is drawn in code. If assets/ui/defeat.png or victory.png exist they are drawn
 * as the background of the ending instead (see Assets).
 *
 * Runs on the Swing thread only.
 */
public class Overlay extends JPanel {
    private enum Mode { NONE, LOBBY, END }

    private static final String[] DIFFICULTY = {"EASY", "NORMAL", "HARD"};

    private final NetworkClient net;
    private Mode mode = Mode.NONE;

    // lobby
    private int crewK, crewE, crewSpec;
    private boolean training = true;
    private int difficulty = 1;
    private final FlatButton ready = new FlatButton("READY");
    private final FlatButton diffBtn = new FlatButton("");
    private final FlatButton modeBtn = new FlatButton("");

    // ending
    private final FlatButton again = new FlatButton("NEW PATROL");
    private String result = "", cause = "", reason = "";
    private int tonnage, fired, hits, dcHits, elapsed, winTonnage = 9000;
    private long startNs;
    private double bonus;                       // seconds skipped by clicking
    private List<double[][]> cracks;
    private final List<double[]> parts = new ArrayList<>();  // {type, x, y, vx, vy, life, maxLife, size}
    private final Random rnd = new Random();

    public Overlay(NetworkClient net) {
        this.net = net;
        setOpaque(false);
        setLayout(null);
        add(ready); add(diffBtn); add(modeBtn); add(again);
        ready.addActionListener(e -> net.command("READY", imReady() ? 0 : 1));
        diffBtn.addActionListener(e -> net.command("DIFFICULTY", (difficulty + 1) % 3));
        modeBtn.addActionListener(e -> net.command("TRAINING", training ? 0 : 1));
        again.addActionListener(e -> net.command("RESTART", 0));
        // A glass pane that is showing swallows the mouse, so nothing underneath can be clicked.
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (mode == Mode.END && elapsedSec() < debriefAt()) bonus += debriefAt() - elapsedSec();
            }
        });
        setVisible(false);
    }

    // ---------------------------------------------------------------- state from the network

    public void setCrew(int k, int e, int spec) { crewK = k; crewE = e; crewSpec = spec; repaint(); }

    /** Called for every STATE message. `prev` is the phase of the one before. */
    public void onState(Snapshot s, String prev) {
        String phase = s.s("phase");
        training = s.b("training");
        difficulty = s.i("difficulty");
        if (s.i("winTonnage") > 0) winTonnage = s.i("winTonnage");
        switch (phase) {
            case "LOBBY" -> {
                mode = Mode.LOBBY;
                parts.clear();
            }
            case "PLAYING" -> { mode = Mode.NONE; parts.clear(); }
            default -> {                                       // WON or LOST
                if (mode != Mode.END) {
                    mode = Mode.END;
                    result = phase; cause = s.s("cause"); reason = s.s("reason");
                    tonnage = s.i("tonnage"); fired = s.i("fired"); hits = s.i("hits");
                    dcHits = s.i("dcHits"); elapsed = s.i("elapsed");
                    startNs = System.nanoTime();
                    bonus = prev.equals("PLAYING") ? 0 : 1000;   // joined late: straight to the debrief
                    cracks = null;
                    parts.clear();
                }
            }
        }
        setVisible(mode != Mode.NONE);
        updateButtons();
        repaint();
    }

    private boolean imReady() {
        return switch (net.getStation()) {
            case "KOMM" -> crewK == 2;
            case "ENG" -> crewE == 2;
            case "ALL" -> crewK == 2 && crewE == 2;
            default -> false;
        };
    }

    private void updateButtons() {
        boolean lobby = mode == Mode.LOBBY;
        boolean spectator = net.getStation().equals("SPEC");
        ready.setVisible(lobby && !spectator);
        diffBtn.setVisible(lobby && !spectator);
        modeBtn.setVisible(lobby && !spectator);
        ready.setText(imReady() ? "READY  -  waiting for crew (click to cancel)" : "READY");
        ready.setHighlight(imReady());
        diffBtn.setText("DIFFICULTY: " + DIFFICULTY[Math.max(0, Math.min(2, difficulty))]);
        modeBtn.setText(training ? "MODE: TRAINING AIDS" : "MODE: REALISTIC 1943");
        again.setVisible(mode == Mode.END && !spectator && elapsedSec() >= debriefAt());
    }

    // ---------------------------------------------------------------- animation clock

    /** Jumps the ending animation to `sec` seconds (used by the screenshot tests). */
    void seek(double sec) { startNs = System.nanoTime(); bonus = sec; }

    private double elapsedSec() { return (System.nanoTime() - startNs) / 1e9 + bonus; }

    private double titleAt() {
        if (result.equals("WON")) return 5.0;
        if (cause.equals("ESCAPED")) return 2.5;
        return sinkSeconds() + 0.8 + 1.6;
    }

    private double debriefAt() { return titleAt() + (cause.equals("ESCAPED") ? 1.5 : 2.0); }

    private double sinkSeconds() { return cause.equals("CRUSH") ? 3.5 : cause.equals("FLOOD") ? 7 : 5; }

    /** Called ~30 times a second by the window's timer: moves the particles. */
    public void tick() {
        if (!isVisible()) return;
        if (mode == Mode.END) {
            spawnParticles(0.033);
            double dt = 0.033;
            for (Iterator<double[]> it = parts.iterator(); it.hasNext(); ) {
                double[] p = it.next();
                p[1] += p[3] * dt; p[2] += p[4] * dt;
                if (p[0] == 1) p[4] += 320 * dt;               // spray falls
                p[5] -= dt;
                if (p[5] <= 0) it.remove();
            }
            updateButtons();
        }
        doLayout();
        repaint();
    }

    private void spawnParticles(double dt) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || parts.size() > 700) return;
        double t = elapsedSec();
        if (result.equals("LOST") && !cause.equals("ESCAPED")) {
            double frac = clamp((t - (cause.equals("FLOOD") ? 0 : 0.5)) / sinkSeconds());
            double level = frac * h;
            if (frac > 0.02) for (int i = 0; i < 3; i++)
                parts.add(new double[]{0, rnd.nextDouble() * w, h - rnd.nextDouble() * level, 0, -(40 + rnd.nextDouble() * 90), 1.5 + rnd.nextDouble() * 1.5, 3, 2 + rnd.nextDouble() * 5});
            if (cracks != null && t > 0.5 && t < sinkSeconds()) {
                for (int c = 0; c < Math.min(4, cracks.size()); c++) {
                    double[] o = cracks.get(c)[0];
                    double dx = w / 2.0 - o[0], dy = h / 2.0 - o[1], len = Math.hypot(dx, dy);
                    double sp = 160 + rnd.nextDouble() * 120;
                    parts.add(new double[]{1, o[0], o[1], dx / len * sp + (rnd.nextDouble() - 0.5) * 60,
                            dy / len * sp + (rnd.nextDouble() - 0.5) * 60 - 40, 0.8 + rnd.nextDouble() * 0.6, 1.4, 2 + rnd.nextDouble() * 2});
                }
            }
        } else if (result.equals("WON")) {
            int n = shipsSunk();
            double horizon = h * 0.62;
            for (int k = 0; k < n; k++) {
                double cx = w * (0.22 + 0.28 * k), sink = clamp((t - 1) / 8) * 45;
                if (t < 12) parts.add(new double[]{2, cx + (rnd.nextDouble() - 0.5) * 20, horizon - 30 + sink, (rnd.nextDouble() - 0.3) * 14, -(30 + rnd.nextDouble() * 25), 4 + rnd.nextDouble() * 3, 7, 6});
                if (t < 7 && rnd.nextDouble() < 0.6) parts.add(new double[]{3, cx + (rnd.nextDouble() - 0.5) * 50, horizon - 12 + sink, 0, -(20 + rnd.nextDouble() * 30), 0.5 + rnd.nextDouble() * 0.5, 1, 3 + rnd.nextDouble() * 4});
            }
        }
    }

    private int shipsSunk() { return Math.max(1, Math.min(3, hits)); }

    // ---------------------------------------------------------------- layout

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        int top = Math.max(24, h / 2 - 230);
        ready.setBounds(w / 2 - 270, top + 342, 540, 42);
        diffBtn.setBounds(w / 2 - 270, top + 392, 265, 34);
        modeBtn.setBounds(w / 2 + 5, top + 392, 265, 34);
        int py = (int) (h * 0.30) + 55;
        again.setBounds(w / 2 - 100, py + 190, 200, 36);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g0) {
        if (mode == Mode.NONE) return;
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        int w = getWidth(), h = getHeight();
        if (mode == Mode.LOBBY) paintLobby(g, w, h);
        else paintEnd(g, w, h, elapsedSec());
        g.dispose();
    }

    private void paintLobby(Graphics2D g, int w, int h) {
        g.setColor(new Color(8, 11, 9, 247));
        g.fillRect(0, 0, w, h);
        Theme.scanlines(g, w, h);
        int top = Math.max(24, h / 2 - 230);
        int cx = w / 2;

        g.setFont(new Font("Monospaced", Font.BOLD, 40));
        g.setColor(Theme.AMBER);
        centred(g, "THE BLEED VALVE", cx, top + 40);
        g.setFont(Theme.LABEL);
        g.setColor(Theme.AMBER_DIM);
        centred(g, "TYPE VII-C   -   NORTH ATLANTIC   -   1943", cx, top + 64);

        g.setFont(Theme.SMALL);
        g.setColor(Theme.INK);
        String[] brief = {
                "ORDERS:  a convoy is crossing westward with a destroyer escort.",
                "Sink at least " + String.format("%,d", winTonnage) + " tons, or destroy the whole convoy.",
                "Dive below the thermocline (80 m) and run silent when the escort comes.",
                "Two crew, two stations. Neither of you can see the whole picture."};
        for (int i = 0; i < brief.length; i++) centred(g, brief[i], cx, top + 104 + i * 18);

        crewRow(g, cx, top + 200, "KOMMANDANT", "periscope  -  torpedoes  -  plot", crewK, "KOMM");
        crewRow(g, cx, top + 252, "LEITSTAND", "engines  -  depth  -  hydrophone  -  damage", crewE, "ENG");

        g.setFont(Theme.LABEL);
        g.setColor(Theme.AMBER_DIM);
        centred(g, "spectators: " + crewSpec, cx, top + 312);

        String st = net.getStation();
        String hint;
        if (st.equals("SPEC")) hint = "You are watching. The seats are taken.";
        else if (crewK == 0 || crewE == 0) hint = "Waiting for the other seat. Solo? Launch a client as ALL.";
        else hint = "Both seats manned. The patrol starts when everyone is READY.";
        g.setColor(Theme.INK);
        centred(g, hint, cx, top + 332);
        g.setColor(Theme.AMBER_DIM);
        centred(g, "you are: " + stationName(st), cx, top + 448);
    }

    private void crewRow(Graphics2D g, int cx, int y, String title, String sub, int state, String seat) {
        Theme.plate(g, cx - 270, y - 24, 540, 44);
        boolean mine = net.getStation().equals(seat) || net.getStation().equals("ALL");
        g.setFont(Theme.LABEL);
        g.setColor(mine ? Theme.PHOSPHOR : Theme.INK);
        g.drawString(title + (mine ? "  (YOU)" : ""), cx - 256, y - 4);
        g.setColor(Theme.AMBER_DIM);
        g.setFont(Theme.SMALL);
        g.drawString(sub, cx - 256, y + 11);
        String label = state == 0 ? "EMPTY" : state == 1 ? "MANNED" : "READY";
        g.setFont(Theme.VALUE);
        g.setColor(state == 0 ? Theme.AMBER_DIM : state == 1 ? Theme.AMBER : Theme.PHOSPHOR);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, cx + 258 - fm.stringWidth(label), y + 6);
    }

    private static String stationName(String s) {
        return switch (s) {
            case "KOMM" -> "the Kommandant";
            case "ENG" -> "the Leitender Ingenieur";
            case "ALL" -> "both stations (solo)";
            default -> "a spectator";
        };
    }

    // ---- ending ----

    private void paintEnd(Graphics2D g, int w, int h, double t) {
        BufferedImage custom = Assets.image(result.equals("WON") ? "ui/victory.png" : "ui/defeat.png");
        boolean win = result.equals("WON");
        if (win) paintWin(g, w, h, t);
        else if (cause.equals("ESCAPED")) paintEscaped(g, w, h, t);
        else paintSink(g, w, h, t);

        if (custom != null && t > titleAt() - 1) {              // your own art fades in behind the text
            float a = (float) clamp((t - titleAt() + 1) / 1.5);
            Graphics2D gc = (Graphics2D) g.create();
            gc.setComposite(AlphaComposite.SrcOver.derive(a * 0.9f));
            gc.drawImage(custom, 0, 0, w, h, null);
            gc.dispose();
        }

        double ta = titleAt();
        if (t >= ta) {
            float a = (float) clamp((t - ta) / 1.2);
            String title, sub = reason;
            if (win) title = cause.equals("PARTIAL") ? "PATROL SUCCESSFUL" : "CONVOY DESTROYED";
            else title = cause.equals("ESCAPED") ? "THE CONVOY GOT AWAY" : "LOST WITH ALL HANDS";
            Graphics2D gt = (Graphics2D) g.create();
            gt.setComposite(AlphaComposite.SrcOver.derive(a));
            gt.setFont(new Font("Monospaced", Font.BOLD, 44));
            gt.setColor(win ? new Color(255, 226, 160) : new Color(230, 70, 55));
            centred(gt, title, w / 2, (int) (h * 0.30));
            gt.setFont(Theme.LABEL);
            gt.setColor(Theme.INK);
            centred(gt, sub, w / 2, (int) (h * 0.30) + 26);
            gt.dispose();
        }
        if (t >= debriefAt()) paintDebrief(g, w, h, (float) clamp((t - debriefAt()) / 0.8));
        else if (t < debriefAt()) {
            g.setFont(Theme.SMALL);
            g.setColor(new Color(255, 255, 255, 70));
            g.drawString("click to skip", w - 100, h - 14);
        }
    }

    private void paintSink(Graphics2D g, int w, int h, double t) {
        boolean flood = cause.equals("FLOOD"), crush = cause.equals("CRUSH");
        double D = sinkSeconds();
        double t0 = flood ? 0 : 0.5;

        // emergency lighting: the whole boat goes dark red and pulses
        g.setColor(new Color(28, 0, 0, (int) (clamp(t / 1.2) * 150)));
        g.fillRect(0, 0, w, h);
        double pulse = 0.5 + 0.5 * Math.sin(t * 7);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(w / 2.0, h / 2.0), (float) Math.hypot(w, h) / 2f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(255, 30, 20, 0), new Color(255, 30, 20, 0), new Color(255, 30, 20, (int) (60 + 70 * pulse))}));
        g.fillRect(0, 0, w, h);

        // cracks racing across the hull
        if (cracks == null) cracks = makeCracks(w, h);
        double cp = clamp((t - 0.1) / 1.6);
        if (!flood || t > 1.5) {
            double drawP = flood ? clamp((t - 1.5) / 3) : cp;
            for (double[][] c : cracks) {
                int n = (int) (drawP * c.length);
                if (n < 2) continue;
                Path2D p = new Path2D.Double();
                p.moveTo(c[0][0], c[0][1]);
                for (int i = 1; i < n; i++) p.lineTo(c[i][0], c[i][1]);
                g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0, 0, 0, 200));
                g.draw(p);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(230, 240, 255, 230));
                g.draw(p);
            }
        }

        // water rising from the bottom, with a moving surface
        double frac = clamp((t - t0) / D), level = frac * (h + 20);
        if (level > 1) {
            Path2D water = new Path2D.Double();
            water.moveTo(0, h);
            for (int x = 0; x <= w; x += 8) {
                double y = h - level + Math.sin(x * 0.021 + t * 3.1) * 8 + Math.sin(x * 0.011 - t * 2.0) * 6;
                water.lineTo(x, y);
            }
            water.lineTo(w, h);
            water.closePath();
            g.setPaint(new GradientPaint(0, (float) (h - level), new Color(36, 100, 120, 200), 0, h, new Color(3, 20, 34, 248)));
            g.fill(water);
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(170, 220, 230, 160));
            Path2D line = new Path2D.Double();
            for (int x = 0; x <= w; x += 8) {
                double y = h - level + Math.sin(x * 0.021 + t * 3.1) * 8 + Math.sin(x * 0.011 - t * 2.0) * 6;
                if (x == 0) line.moveTo(x, y); else line.lineTo(x, y);
            }
            g.draw(line);
        }
        drawParticles(g);

        // the implosion / hull failure flash
        if (!flood) {
            double fl = clamp(1 - t / (crush ? 0.9 : 0.5));
            if (fl > 0) { g.setColor(new Color(255, 255, 255, (int) (fl * 255))); g.fillRect(0, 0, w, h); }
        }

        double fs = D + 0.8;
        if (t > fs) {
            g.setColor(new Color(0, 0, 0, (int) (clamp((t - fs) / 1.4) * 255)));
            g.fillRect(0, 0, w, h);
        }
    }

    private List<double[][]> makeCracks(int w, int h) {
        List<double[][]> list = new ArrayList<>();
        Random r = new Random(7);
        for (int i = 0; i < 8; i++) {
            double x, y;
            switch (r.nextInt(4)) {
                case 0 -> { x = r.nextDouble() * w; y = 0; }
                case 1 -> { x = w; y = r.nextDouble() * h; }
                case 2 -> { x = r.nextDouble() * w; y = h; }
                default -> { x = 0; y = r.nextDouble() * h; }
            }
            double ang = Math.atan2(h / 2.0 - y, w / 2.0 - x) + (r.nextDouble() - 0.5) * 0.8;
            double[][] pts = new double[16][2];
            for (int k = 0; k < pts.length; k++) {
                pts[k][0] = x; pts[k][1] = y;
                double step = 35 + r.nextDouble() * 40;
                ang += (r.nextDouble() - 0.5) * 0.9;
                x += Math.cos(ang) * step; y += Math.sin(ang) * step;
            }
            list.add(pts);
        }
        return list;
    }

    private void paintEscaped(Graphics2D g, int w, int h, double t) {
        g.setColor(new Color(16, 24, 40, (int) (clamp(t / 2.5) * 235)));
        g.fillRect(0, 0, w, h);
        double horizon = h * 0.62;
        g.setColor(new Color(26, 38, 60, (int) (clamp(t / 2.5) * 255)));
        g.fillRect(0, (int) horizon, w, h);
        g.setColor(new Color(90, 110, 140, (int) (clamp(t / 2.5) * 120)));
        g.drawLine(0, (int) horizon, w, (int) horizon);
        // the convoy sailing off into the dusk, out of reach
        Graphics2D gs = (Graphics2D) g.create();
        gs.setComposite(AlphaComposite.SrcOver.derive((float) clamp(t / 1.5)));
        for (int k = 0; k < 3; k++) {
            double x = w * 0.30 + k * 110 - t * 22;
            silhouette(gs, x, horizon, 0.5 + 0.1 * (k % 2), 0, 0, new Color(6, 8, 14));
        }
        gs.dispose();
    }

    private void paintWin(Graphics2D g, int w, int h, double t) {
        double a = clamp(t / 3.0);
        double horizon = h * 0.62;
        Graphics2D ga = (Graphics2D) g.create();
        ga.setComposite(AlphaComposite.SrcOver.derive((float) a));
        ga.setPaint(new GradientPaint(0, 0, new Color(14, 26, 64), 0, (float) horizon, new Color(250, 150, 70)));
        ga.fillRect(0, 0, w, (int) horizon + 1);

        double sunY = horizon + 30 - clamp(t / 9.0) * (h * 0.20), sunX = w * 0.62;
        ga.setPaint(new RadialGradientPaint(new Point2D.Double(sunX, sunY), (float) (h * 0.40),
                new float[]{0f, 0.25f, 1f},
                new Color[]{new Color(255, 220, 150, 230), new Color(255, 160, 80, 110), new Color(255, 120, 60, 0)}));
        ga.fillRect(0, 0, w, (int) horizon + 1);
        Shape oldClip = ga.getClip();
        ga.clipRect(0, 0, w, (int) horizon);
        ga.setColor(new Color(255, 238, 190));
        ga.fill(new Ellipse2D.Double(sunX - 34, sunY - 34, 68, 68));
        ga.setClip(oldClip);

        ga.setPaint(new GradientPaint(0, (float) horizon, new Color(190, 100, 58), 0, h, new Color(8, 20, 44)));
        ga.fillRect(0, (int) horizon, w, h - (int) horizon);
        ga.setStroke(new BasicStroke(1.6f));
        for (int i = 0; i < 16; i++) {                           // the sun's reflection, broken by ripples
            double y = horizon + 6 + i * i * 1.5;
            double len = 40 + i * 9 + Math.sin(t * 2 + i) * 14;
            ga.setColor(new Color(255, 214, 140, Math.max(20, 150 - i * 8)));
            ga.drawLine((int) (sunX - len / 2 + Math.sin(t + i) * 6), (int) y, (int) (sunX + len / 2 + Math.sin(t + i) * 6), (int) y);
        }

        // the wrecks: tilting and sliding under the waterline
        ga.clipRect(0, 0, w, (int) horizon + 1);
        int n = shipsSunk();
        for (int k = 0; k < n; k++) {
            double cx = w * (0.22 + 0.28 * k);
            double tilt = clamp((t - 0.8 - 0.6 * k) / 6.0) * 0.55 * (k % 2 == 0 ? 1 : -1);
            double sink = clamp((t - 1 - 0.5 * k) / 8) * 48;
            silhouette(ga, cx, horizon, 0.9 + 0.25 * (k % 2), tilt, sink, new Color(7, 8, 13));
        }
        ga.setClip(oldClip);
        drawParticles(ga);
        ga.dispose();
    }

    /** A freighter drawn as a flat black shape: hull, bridge, funnel, two masts. */
    private void silhouette(Graphics2D g, double cx, double waterline, double scale, double tilt, double sink, Color c) {
        Graphics2D s = (Graphics2D) g.create();
        AffineTransform at = new AffineTransform();
        at.translate(cx, waterline + sink);
        at.rotate(tilt);
        at.scale(scale, scale);
        s.transform(at);
        s.setColor(c);
        Path2D hull = new Path2D.Double();
        hull.moveTo(-70, 0); hull.lineTo(70, 0); hull.lineTo(84, -17); hull.lineTo(-76, -17); hull.closePath();
        s.fill(hull);
        s.fill(new Rectangle2D.Double(-22, -36, 42, 19));
        s.fill(new Rectangle2D.Double(-6, -54, 12, 19));
        s.setStroke(new BasicStroke(1.8f));
        s.drawLine(42, -17, 42, -62);
        s.drawLine(-48, -17, -48, -56);
        s.drawLine(-48, -52, 42, -58);
        s.dispose();
    }

    private void drawParticles(Graphics2D g) {
        for (double[] p : parts) {
            double life = clamp(p[5] / p[6]);
            int type = (int) p[0];
            double r = p[7];
            switch (type) {
                case 0 -> {                                       // bubble
                    g.setStroke(new BasicStroke(1.2f));
                    g.setColor(new Color(200, 235, 245, (int) (130 * life)));
                    g.draw(new Ellipse2D.Double(p[1] - r, p[2] - r, r * 2, r * 2));
                }
                case 1 -> {                                       // spray from a crack
                    g.setColor(new Color(215, 240, 250, (int) (210 * life)));
                    g.fill(new Ellipse2D.Double(p[1] - r, p[2] - r, r * 2, r * 2));
                }
                case 2 -> {                                       // smoke grows as it rises
                    double rr = r + (1 - life) * 26;
                    g.setColor(new Color(40, 38, 40, (int) (95 * life)));
                    g.fill(new Ellipse2D.Double(p[1] - rr, p[2] - rr, rr * 2, rr * 2));
                }
                default -> {                                      // flame
                    g.setColor(new Color(255, (int) (110 + 110 * life), 40, (int) (220 * life)));
                    g.fill(new Ellipse2D.Double(p[1] - r, p[2] - r, r * 2, r * 2));
                }
            }
        }
    }

    private void paintDebrief(Graphics2D g, int w, int h, float alpha) {
        Graphics2D gd = (Graphics2D) g.create();
        gd.setComposite(AlphaComposite.SrcOver.derive(alpha));
        int pw = 480, ph = 240, px = w / 2 - pw / 2, py = (int) (h * 0.30) + 55;
        gd.setColor(new Color(8, 11, 9, 225));
        gd.fillRoundRect(px, py, pw, ph, 10, 10);
        Theme.plate(gd, px, py, pw, ph);
        gd.setFont(Theme.LABEL);
        gd.setColor(Theme.AMBER);
        gd.drawString("PATROL REPORT", px + 18, py + 26);

        String acc = fired == 0 ? "-" : (hits * 100 / fired) + "% accuracy";
        String[][] rows = {
                {"TONNAGE SUNK", String.format("%,d t", tonnage)},
                {"TORPEDOES", fired + " fired,  " + hits + " hit   (" + acc + ")"},
                {"DEPTH-CHARGE HITS TAKEN", String.valueOf(dcHits)},
                {"TIME ON PATROL", String.format("%d:%02d", elapsed / 60, elapsed % 60)},
                {"MODE", (training ? "TRAINING AIDS" : "REALISTIC 1943") + "  /  " + DIFFICULTY[Math.max(0, Math.min(2, difficulty))]}};
        gd.setFont(Theme.SMALL);
        for (int i = 0; i < rows.length; i++) {
            int y = py + 56 + i * 24;
            gd.setColor(Theme.AMBER_DIM);
            gd.drawString(rows[i][0], px + 18, y);
            gd.setColor(Theme.INK);
            FontMetrics fm = gd.getFontMetrics();
            gd.drawString(rows[i][1], px + pw - 18 - fm.stringWidth(rows[i][1]), y);
        }
        String verdict = result.equals("WON")
                ? (cause.equals("ALL") ? "Outstanding. The convoy is finished." : "A successful patrol.")
                : tonnage > 0 ? "The boat is lost, but not for nothing." : "Nothing to show for it.";
        gd.setFont(Theme.LABEL);
        gd.setColor(result.equals("WON") ? Theme.PHOSPHOR : Theme.ALERT);
        centred(gd, verdict, w / 2, py + 170);
        gd.dispose();
    }

    private static void centred(Graphics2D g, String s, int cx, int y) {
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    /** A button drawn in the instrument style (the system look-and-feel ignores custom colours). */
    static class FlatButton extends JButton {
        private boolean highlight;
        FlatButton(String text) {
            super(text);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFont(Theme.LABEL);
        }
        void setHighlight(boolean h) { highlight = h; }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.smooth((Graphics2D) g0.create());
            boolean hot = getModel().isRollover() || highlight;
            g.setColor(highlight ? new Color(20, 60, 36) : Theme.STEEL);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setColor(hot ? Theme.AMBER : Theme.STEEL_EDGE);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setFont(getFont());
            g.setColor(highlight ? Theme.PHOSPHOR : Theme.AMBER);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2, (getHeight() + fm.getAscent()) / 2 - 2);
            g.dispose();
        }
    }
}
