import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.util.List;
import javax.swing.JComponent;

/**
 * The attack periscope.
 *
 * In realistic mode the server sends, for each ship, only what light through a lens can tell
 * you: a bearing, an angular height (masthead to waterline) and an angular width. There is no
 * range and no course in the data at all. The silhouette is drawn to exactly those angles, so
 * measuring it — which is what the stadimeter does — is the only way to learn how far away she is.
 *
 * Two powers, like the real instrument: 1.5x to search, 6x to identify and measure.
 */
public class PeriscopeView extends JComponent {
    private double periscopeBearing, subHeading, depth, swell;
    private boolean canSee, scopeUp, training, highPower;
    private List<Snapshot.Contact> contacts = List.of();

    public PeriscopeView() {
        setPreferredSize(new Dimension(520, 520));
        setOpaque(false);
    }

    public void update(double periscopeBearing, double subHeading, boolean canSee, boolean scopeUp,
                       double depth, boolean training, List<Snapshot.Contact> contacts) {
        this.periscopeBearing = periscopeBearing;
        this.subHeading = subHeading;
        this.canSee = canSee;
        this.scopeUp = scopeUp;
        this.depth = depth;
        this.training = training;
        this.contacts = contacts;
    }

    public void toggleZoom() { highPower = !highPower; }
    public void tick() { swell += 0.05; }

    private double fov() { return highPower ? 9 : 36; }

    /** The ship nearest the vertical wire, or null. This is what the stadimeter measures. */
    public Snapshot.Contact centred() {
        Snapshot.Contact best = null;
        double bestRel = fov() * 0.12;
        for (Snapshot.Contact c : contacts) {
            double rel = Math.abs(GameMath.angleDiff(c.bearing, periscopeBearing));
            if (rel < bestRel) { bestRel = rel; best = c; }
        }
        return best;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        int w = getWidth(), h = getHeight();
        int d = Math.min(w, h) - 86;
        int cx = w / 2, cy = h / 2;
        double r = d / 2.0;

        g.setColor(Theme.STEEL_DARK);
        g.fillRect(0, 0, w, h);

        Shape lens = new Ellipse2D.Double(cx - r, cy - r, d, d);
        Shape oldClip = g.getClip();
        g.setClip(lens);
        if (canSee) { drawSea(g, cx, cy, r); Theme.scanlines(g, w, h); }
        else drawBlackout(g, cx, cy, r);
        g.setClip(oldClip);
        drawBezel(g, cx, cy, r);
        g.dispose();
    }

    private void drawSea(Graphics2D g, int cx, int cy, double r) {
        int horizon = cy - 10;
        g.setPaint(new GradientPaint(0, (float) (cy - r), new Color(0x24, 0x33, 0x3A),
                                     0, horizon, new Color(0x56, 0x62, 0x5E)));
        g.fillRect((int) (cx - r), (int) (cy - r), (int) (r * 2), horizon - (int) (cy - r));
        g.setPaint(new GradientPaint(0, horizon, new Color(0x1B, 0x2A, 0x2B),
                                     0, (float) (cy + r), new Color(0x07, 0x0E, 0x10)));
        g.fillRect((int) (cx - r), horizon, (int) (r * 2), (int) (cy + r) - horizon);
        g.setColor(new Color(0x6E, 0x7D, 0x74));
        g.setStroke(new BasicStroke(1.4f));
        g.drawLine((int) (cx - r), horizon, (int) (cx + r), horizon);

        double pxPerDeg = 2 * r / fov();
        double pxPerMrad = pxPerDeg * 0.0573;
        contacts.stream()
                .sorted((a, b) -> Double.compare(a.mast, b.mast))      // small (far) first
                .forEach(c -> drawShip(g, c, cx, horizon, r, pxPerDeg, pxPerMrad));

        g.setColor(new Color(0xFF, 0xFF, 0xFF, 18));
        g.setStroke(new BasicStroke(1f));
        for (int i = 1; i <= 6; i++) {
            int y = horizon + i * i * 3;
            int off = (int) (Math.sin(swell + i) * 12);
            g.drawLine((int) (cx - r), y + off / 3, (int) (cx + r), y - off / 3);
        }
        drawReticle(g, cx, cy, horizon, r, pxPerDeg);
    }

    private void drawShip(Graphics2D g, Snapshot.Contact c, int cx, int horizon, double r,
                          double pxPerDeg, double pxPerMrad) {
        double rel = GameMath.angleDiff(c.bearing, periscopeBearing);
        if (Math.abs(rel) > fov() / 2 + 3) return;
        double x = cx + rel * pxPerDeg;
        double mastPx = Math.max(2, c.mast * pxPerMrad);
        double widPx = Math.max(2, c.wid * pxPerMrad);
        double bob = Math.sin(swell * 0.8 + c.id) * Math.min(2, mastPx * 0.04);
        double baseY = horizon + 1 + bob;

        // Distant ships fade into the haze. Judged from angular size, since that is all we have.
        double haze = Math.max(0.35, Math.min(1, c.mast / 14.0));
        Color hull = Theme.blend(new Color(0x48, 0x55, 0x55), new Color(0x12, 0x16, 0x17), haze);
        ShipClass.draw(g, c.cls, x, baseY, widPx, mastPx, c.dir, hull);

        // The one honest clue to her course: a bow wave if she is coming toward us, a wake if going away.
        g.setColor(new Color(0xE8, 0xF0, 0xEC, (int) (150 * haze)));
        g.setStroke(new BasicStroke(1.3f));
        double end = x + c.dir * widPx * 0.5;
        if (c.near) {
            g.drawLine((int) end, (int) baseY, (int) (end + c.dir * Math.max(2, mastPx * 0.18)), (int) baseY + 1);
            g.drawLine((int) end, (int) baseY - 1, (int) (end - c.dir * Math.max(2, mastPx * 0.2)), (int) baseY + 1);
        } else {
            double stern = x - c.dir * widPx * 0.5;
            g.drawLine((int) stern, (int) baseY + 1, (int) (stern - c.dir * Math.max(3, mastPx * 0.5)), (int) baseY + 1);
        }

        if (training && c.range > 0) {
            g.setFont(Theme.SMALL);
            g.setColor(c.cls == 3 ? Theme.ALERT : Theme.AMBER);
            String a = c.name + "  " + Math.round(c.range) + " m";
            String b = String.format("AOB %d %s  %.1f kn", Math.round(Math.abs(c.aob)), c.aob >= 0 ? "stbd" : "port", c.kn);
            int ty = (int) (baseY - mastPx - 20 - (c.id % 3) * 26);        // staggered so neighbours don't collide
            g.drawString(a, (int) (x - g.getFontMetrics().stringWidth(a) / 2.0), ty);
            g.drawString(b, (int) (x - g.getFontMetrics().stringWidth(b) / 2.0), ty + 12);
        }
    }

    private void drawReticle(Graphics2D g, int cx, int cy, int horizon, double r, double pxPerDeg) {
        g.setColor(new Color(0x10, 0x10, 0x10, 170));
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(cx, (int) (cy - r), cx, (int) (cy + r));
        g.drawLine((int) (cx - r), horizon, (int) (cx + r), horizon);
        for (int i = 1; i <= 8; i++) {                       // stadimeter ladder
            int yy = horizon - i * 14;
            int half = i % 2 == 0 ? 14 : 7;
            g.drawLine(cx - half, yy, cx + half, yy);
        }
        g.setFont(Theme.SMALL);
        int step = highPower ? 1 : 5, span = highPower ? 4 : 15;
        for (int b = -span; b <= span; b += step) {
            double x = cx + b * pxPerDeg;
            g.drawLine((int) x, horizon - 5, (int) x, horizon + 5);
            if (b != 0) g.drawString(String.valueOf((int) GameMath.norm(Math.round(periscopeBearing) + b)), (int) x - 8, horizon + 20);
        }
        g.setColor(new Color(0xD8, 0xE0, 0xD8, 150));
        g.drawString(highPower ? "6x" : "1.5x", (int) (cx + r * 0.55), (int) (cy + r * 0.6));
    }

    private void drawBlackout(Graphics2D g, int cx, int cy, double r) {
        g.setColor(new Color(0x05, 0x08, 0x09));
        g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
        g.setFont(Theme.BIG);
        g.setColor(Theme.AMBER_DIM);
        String s = "PERISCOPE DOWN";
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, cy - 6);
        g.setFont(Theme.LABEL);
        String t = depth > 15 ? "Depth " + Math.round(depth) + " m - come up to 15 m or less"
                              : "Press W to raise it. A raised scope can be seen.";
        g.drawString(t, cx - g.getFontMetrics().stringWidth(t) / 2, cy + 18);
    }

    private void drawBezel(Graphics2D g, int cx, int cy, double r) {
        g.setStroke(new BasicStroke(10f));
        g.setColor(new Color(0x2A, 0x24, 0x18));
        g.drawOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
        g.setStroke(new BasicStroke(2f));
        g.setColor(Theme.BRASS);
        g.drawOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));

        double ring = r + 14;
        g.setFont(Theme.SMALL);
        for (int b = 0; b < 360; b += 10) {
            double rel = GameMath.angleDiff(b, periscopeBearing);
            if (Math.abs(rel) > 24) continue;
            double a = Math.toRadians(90 - rel * 2.6);
            boolean major = b % 30 == 0;
            g.setColor(major ? Theme.BRASS : Theme.AMBER_DIM);
            g.setStroke(new BasicStroke(major ? 2f : 1f));
            double r1 = ring + (major ? 4 : 8), r2 = ring + 13;
            g.drawLine((int) (cx + Math.cos(a) * r1), (int) (cy - Math.sin(a) * r1),
                       (int) (cx + Math.cos(a) * r2), (int) (cy - Math.sin(a) * r2));
            if (major) {
                String t = String.valueOf(b);
                g.drawString(t, (int) (cx + Math.cos(a) * (ring + 24)) - g.getFontMetrics().stringWidth(t) / 2,
                                (int) (cy - Math.sin(a) * (ring + 24)) + 4);
            }
        }
        g.setColor(Theme.AMBER);
        g.fillPolygon(new int[]{cx - 6, cx + 6, cx}, new int[]{(int) (cy - ring - 20), (int) (cy - ring - 20), (int) (cy - ring - 10)}, 3);

        g.setFont(Theme.LABEL);
        g.setColor(Theme.INK);
        String hdg = String.format("BEARING %03d   BOAT %03d   %s",
                Math.round(periscopeBearing) % 360, Math.round(subHeading) % 360,
                depth < 1 ? "BRIDGE WATCH" : scopeUp ? "SCOPE UP" : "SCOPE DOWN");
        g.drawString(hdg, cx - g.getFontMetrics().stringWidth(hdg) / 2, (int) (cy + r + 34));
    }
}
