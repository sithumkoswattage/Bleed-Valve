import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Shared colours, fonts and small drawing helpers, so every panel looks like one instrument suite. */
public class Theme {
    public static final Color STEEL_DARK = new Color(0x0B0E0C);
    public static final Color STEEL      = new Color(0x161B18);
    public static final Color STEEL_EDGE = new Color(0x2A322C);
    public static final Color AMBER      = new Color(0xFFB642);
    public static final Color AMBER_DIM  = new Color(0x7A5A22);
    public static final Color PHOSPHOR   = new Color(0x4FE08A);
    public static final Color ALERT      = new Color(0xFF4B3E);
    public static final Color BRASS      = new Color(0xC9A227);
    public static final Color INK        = new Color(0xD8E0D8);

    public static final Font LABEL  = new Font("Monospaced", Font.BOLD, 11);
    public static final Font VALUE  = new Font("Monospaced", Font.BOLD, 20);
    public static final Font SMALL  = new Font("Monospaced", Font.PLAIN, 11);
    public static final Font BIG    = new Font("Monospaced", Font.BOLD, 28);

    public static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    /** A recessed metal plate: the background every instrument sits on. */
    public static void plate(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(STEEL);
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(STEEL_EDGE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, w, h, 10, 10);
    }

    /** Faint scanlines over the whole panel: the CRT look, drawn last. */
    public static void scanlines(Graphics2D g, int w, int h) {
        g.setColor(new Color(0, 0, 0, 28));
        for (int y = 0; y < h; y += 3) g.drawLine(0, y, w, y);
    }

    /** Interpolates green -> amber -> red as `v` falls from 1 to 0. */
    public static Color level(double v) {
        v = Math.max(0, Math.min(1, v));
        if (v > 0.5) return blend(AMBER, PHOSPHOR, (v - 0.5) * 2);
        return blend(ALERT, AMBER, v * 2);
    }

    public static Color blend(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
