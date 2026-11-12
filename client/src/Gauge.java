import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;

/**
 * A round instrument dial with a swinging needle.
 *
 * The needle eases toward the value instead of snapping to it, so the dial
 * behaves like a real mechanical instrument. `tick()` is called ~30 times a
 * second by the panel's repaint timer.
 */
public class Gauge extends JComponent {
    private final String label;
    private final String unit;
    private final double min, max;
    private final double sweep;          // degrees of dial the needle travels
    private double target, shown;
    private String caption = "";
    private boolean alert = false;

    public Gauge(String label, String unit, double min, double max) {
        this(label, unit, min, max, 220);
    }

    public Gauge(String label, String unit, double min, double max, double sweep) {
        this.label = label; this.unit = unit;
        this.min = min; this.max = max; this.sweep = sweep;
        setPreferredSize(new Dimension(170, 170));
        setOpaque(false);
    }

    public void setValue(double v) { target = v; }
    public void setCaption(String c) { caption = c == null ? "" : c; }
    public void setAlert(boolean a) { alert = a; }

    /** Moves the needle a fraction of the way to the target: a simple smoothing filter. */
    public void tick() {
        shown += (target - shown) * 0.18;
        if (Math.abs(target - shown) < 0.01) shown = target;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        int w = getWidth(), h = getHeight();
        int d = Math.min(w, h) - 8;
        int cx = w / 2, cy = h / 2;
        double r = d / 2.0;

        // face
        g.setColor(new Color(0x10, 0x16, 0x12));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
        g.setColor(alert ? Theme.ALERT : Theme.STEEL_EDGE);
        g.setStroke(new BasicStroke(alert ? 2.5f : 2f));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, d, d));

        // ticks
        double start = 90 + sweep / 2;           // 0 at lower-left, sweeping clockwise
        for (int i = 0; i <= 10; i++) {
            double a = Math.toRadians(start - sweep * i / 10.0);
            boolean major = i % 5 == 0;
            double r1 = r - (major ? 14 : 8), r2 = r - 3;
            g.setColor(major ? Theme.INK : Theme.AMBER_DIM);
            g.setStroke(new BasicStroke(major ? 2f : 1f));
            g.drawLine((int) (cx + Math.cos(a) * r1), (int) (cy - Math.sin(a) * r1),
                       (int) (cx + Math.cos(a) * r2), (int) (cy - Math.sin(a) * r2));
            if (major) {
                String t = String.valueOf((int) (min + (max - min) * i / 10.0));
                g.setFont(Theme.SMALL);
                int tw = g.getFontMetrics().stringWidth(t);
                g.drawString(t, (int) (cx + Math.cos(a) * (r - 24)) - tw / 2,
                                 (int) (cy - Math.sin(a) * (r - 24)) + 4);
            }
        }

        // needle: a short counterweight tail makes it read as a balanced instrument
        double frac = (shown - min) / (max - min);
        frac = Math.max(0, Math.min(1, frac));
        double a = Math.toRadians(start - sweep * frac);
        g.setColor(alert ? Theme.ALERT : Theme.AMBER);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) (cx - Math.cos(a) * 12), (int) (cy + Math.sin(a) * 12),
                   (int) (cx + Math.cos(a) * (r - 30)), (int) (cy - Math.sin(a) * (r - 30)));
        g.setColor(Theme.BRASS);
        g.fillOval(cx - 4, cy - 4, 8, 8);

        // Text sits in the middle of the face, inside the tick ring, so nothing collides.
        g.setFont(Theme.LABEL);
        g.setColor(Theme.INK);
        center(g, label, cx, cy - (int) (r * 0.42));
        g.setFont(Theme.VALUE);
        g.setColor(alert ? Theme.ALERT : Theme.PHOSPHOR);
        center(g, fmt(target) + unit, cx, cy + (int) (r * 0.42));
        if (!caption.isEmpty()) {
            g.setFont(Theme.SMALL);
            g.setColor(Theme.AMBER_DIM);
            center(g, caption, cx, cy + (int) (r * 0.66));
        }
        g.dispose();
    }

    private String fmt(double v) {
        return (max - min) <= 20 ? String.format("%.1f", v) : String.valueOf(Math.round(v));
    }

    private void center(Graphics2D g, String s, int cx, int y) {
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
    }
}
