import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/** A horizontal level bar: battery, hull integrity, flooding. */
public class Bar extends JComponent {
    private final String label;
    private final boolean invert;     // true = high value is BAD (flooding)
    private double target, shown;

    public Bar(String label, boolean invert) {
        this.label = label;
        this.invert = invert;
        setPreferredSize(new Dimension(220, 44));
        setOpaque(false);
    }

    public void setValue(double v) { target = v; }

    public void tick() { shown += (target - shown) * 0.2; }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        int w = getWidth(), h = getHeight();
        int barY = h - 16, barH = 12;

        g.setFont(Theme.LABEL);
        g.setColor(Theme.INK);
        g.drawString(label, 0, 14);

        String txt = Math.round(target) + "%";
        g.setFont(Theme.LABEL);
        g.setColor(Theme.PHOSPHOR);
        g.drawString(txt, w - g.getFontMetrics().stringWidth(txt), 14);

        g.setColor(new Color(0x10, 0x16, 0x12));
        g.fillRect(0, barY, w, barH);

        double frac = Math.max(0, Math.min(1, shown / 100.0));
        Color c = Theme.level(invert ? 1 - frac : frac);
        g.setColor(c);
        g.fillRect(0, barY, (int) (w * frac), barH);

        // segment dividers, so it reads as an analogue meter rather than a progress bar
        g.setColor(Theme.STEEL_DARK);
        for (int i = 1; i < 10; i++) g.drawLine(w * i / 10, barY, w * i / 10, barY + barH);

        g.setColor(Theme.STEEL_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(0, barY, w - 1, barH);
        g.dispose();
    }
}
