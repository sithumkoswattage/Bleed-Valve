import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * The plotting table.
 *
 * Nothing appears here by itself. Each time the commander calls a bearing and a range, the
 * navigator pencils a mark. Two marks a known time apart give the target's course and speed —
 * which is how a real boat worked out its firing solution, and why one bad range ruins it.
 *
 * Own position comes from dead reckoning, which the boat always had. In training mode the true
 * positions are ghosted in so the player can see how far off their marks are.
 */
public class AttackPlot extends JComponent {
    private static final double MAX_RANGE = 6000;

    /** One pencilled observation. */
    public record Mark(double x, double y, long at) { }

    private final List<Mark> marks = new ArrayList<>();
    private final List<double[]> track = new ArrayList<>();
    private List<Snapshot.Contact> truth = List.of();
    private double ownX, ownY, boatHeading, scopeBearing;
    private boolean training;
    private long lastTrackAt;

    public AttackPlot() {
        setPreferredSize(new Dimension(250, 214));
        setOpaque(false);
    }

    public void update(double ownX, double ownY, double boatHeading, double scopeBearing,
                       boolean training, List<Snapshot.Contact> truth) {
        this.ownX = ownX; this.ownY = ownY;
        this.boatHeading = boatHeading; this.scopeBearing = scopeBearing;
        this.training = training; this.truth = truth;
        long now = System.currentTimeMillis();
        if (now - lastTrackAt > 4000) {
            lastTrackAt = now;
            track.add(new double[]{ownX, ownY});
            if (track.size() > 150) track.remove(0);
        }
    }

    public void mark(double bearing, double range) {
        double a = Math.toRadians(bearing);
        marks.add(new Mark(ownX + Math.sin(a) * range, ownY + Math.cos(a) * range, System.currentTimeMillis()));
        if (marks.size() > 8) marks.remove(0);
        repaint();
    }

    public void clear() { marks.clear(); repaint(); }

    /** @return {course degrees, speed knots} from the last two marks, or null if there are not two yet. */
    public double[] solution() {
        if (marks.size() < 2) return null;
        Mark a = marks.get(marks.size() - 2), b = marks.get(marks.size() - 1);
        double dt = (b.at() - a.at()) / 1000.0;
        if (dt < 8) return null;                                // too close together to mean anything
        double course = GameMath.norm(Math.toDegrees(Math.atan2(b.x() - a.x(), b.y() - a.y())));
        double speed = Math.hypot(b.x() - a.x(), b.y() - a.y()) / dt * 1.944;
        return new double[]{course, speed};
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        Theme.plate(g, 0, 0, getWidth() - 1, getHeight() - 1);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.AMBER_DIM);
        g.drawString("PLOTTING TABLE", 10, 15);

        int cx = getWidth() / 2, cy = getHeight() / 2 + 8;
        double r = Math.min(getWidth(), getHeight() - 30) / 2.0 - 8;
        double scale = r / MAX_RANGE;

        g.setColor(new Color(0x1C, 0x24, 0x1F));
        for (int i = 1; i <= 3; i++) {
            double rr = r * i / 3.0;
            g.drawOval((int) (cx - rr), (int) (cy - rr), (int) (rr * 2), (int) (rr * 2));
        }
        g.setColor(Theme.STEEL_EDGE);
        g.drawString("2 km", cx + 2, (int) (cy - r / 3) - 2);
        g.drawString("6", cx + 2, (int) (cy - r) + 10);

        // the line of sight
        double a = Math.toRadians(90 - scopeBearing);
        g.setColor(new Color(0xFF, 0xB6, 0x42, 60));
        g.drawLine(cx, cy, (int) (cx + Math.cos(a) * r), (int) (cy - Math.sin(a) * r));

        // own track by dead reckoning
        g.setColor(new Color(0x4F, 0xE0, 0x8A, 90));
        for (double[] p : track) {
            int x = (int) (cx + (p[0] - ownX) * scale), y = (int) (cy - (p[1] - ownY) * scale);
            if (Math.hypot(x - cx, y - cy) < r) g.fillRect(x, y, 2, 2);
        }
        double ba = Math.toRadians(90 - boatHeading);
        g.setColor(Theme.PHOSPHOR);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(cx, cy, (int) (cx + Math.cos(ba) * 13), (int) (cy - Math.sin(ba) * 13));
        g.fillOval(cx - 3, cy - 3, 6, 6);

        if (training) {                                           // ghosts of where things really are
            for (Snapshot.Contact c : truth) {
                if (c.range <= 0 || c.range > MAX_RANGE) continue;
                double ca = Math.toRadians(90 - c.bearing);
                int x = (int) (cx + Math.cos(ca) * c.range * scale), y = (int) (cy - Math.sin(ca) * c.range * scale);
                g.setColor(c.cls == 3 ? new Color(0xFF, 0x4B, 0x3E, 150) : new Color(0xFF, 0xB6, 0x42, 120));
                g.setStroke(new BasicStroke(1f));
                g.drawRect(x - 3, y - 3, 6, 6);
                double ha = Math.toRadians(90 - c.heading);
                g.drawLine(x, y, (int) (x + Math.cos(ha) * 12), (int) (y - Math.sin(ha) * 12));
            }
        }

        // the pencil marks, joined in order
        g.setStroke(new BasicStroke(1.2f));
        int px = 0, py = 0, n = 0;
        for (Mark m : marks) {
            int x = (int) (cx + (m.x() - ownX) * scale), y = (int) (cy - (m.y() - ownY) * scale);
            g.setColor(Theme.INK);
            g.drawLine(x - 4, y - 4, x + 4, y + 4);
            g.drawLine(x - 4, y + 4, x + 4, y - 4);
            g.drawString(String.valueOf(++n), x + 6, y - 4);
            if (n > 1) { g.setColor(new Color(0xD8, 0xE0, 0xD8, 110)); g.drawLine(px, py, x, y); }
            px = x; py = y;
        }

        double[] sol = solution();
        g.setColor(sol != null ? Theme.PHOSPHOR : Theme.STEEL_EDGE);
        g.drawString(sol != null ? String.format("plot: course %03d  %.1f kn", Math.round(sol[0]) % 360, sol[1])
                                 : marks.isEmpty() ? "no marks - take a range, then MARK" : "one mark - wait, then mark again",
                     10, getHeight() - 8);
        g.dispose();
    }
}
