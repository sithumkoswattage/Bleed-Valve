import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/** A switchboard meter: a linear scale with a pointer. Used for battery volts and amps. */
public class Meter extends JComponent {
    private final String label, unit;
    private final double min, max;
    private double target, shown;

    public Meter(String label, String unit, double min, double max) {
        this.label = label; this.unit = unit; this.min = min; this.max = max;
        shown = target = min;
        setPreferredSize(new Dimension(220, 48));
        setOpaque(false);
    }

    public void setValue(double v) { target = v; }
    public void tick() { shown += (target - shown) * 0.2; }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        int w = getWidth(), y = 30;
        g.setFont(Theme.LABEL);
        g.setColor(Theme.INK);
        g.drawString(label, 0, 13);
        String txt = Math.round(target) + " " + unit;
        g.setColor(Theme.PHOSPHOR);
        g.drawString(txt, w - g.getFontMetrics().stringWidth(txt), 13);

        g.setColor(new Color(0x10, 0x16, 0x12));
        g.fillRect(0, y - 8, w, 16);
        g.setColor(Theme.STEEL_EDGE);
        g.drawRect(0, y - 8, w - 1, 16);
        g.setFont(Theme.SMALL);
        for (int i = 0; i <= 10; i++) {
            int x = (int) ((w - 1) * i / 10.0);
            g.setColor(i % 5 == 0 ? Theme.INK : Theme.AMBER_DIM);
            g.drawLine(x, y - 8, x, y - (i % 5 == 0 ? 0 : 3));
        }
        // zero mark, for a meter that reads both ways (charging / discharging)
        if (min < 0 && max > 0) {
            int zx = (int) ((w - 1) * (0 - min) / (max - min));
            g.setColor(Theme.BRASS);
            g.drawLine(zx, y - 8, zx, y + 8);
        }
        double f = Math.max(0, Math.min(1, (shown - min) / (max - min)));
        int px = (int) ((w - 1) * f);
        g.setColor(Theme.AMBER);
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(px, y - 7, px, y + 7);
        g.fillPolygon(new int[]{px - 5, px + 5, px}, new int[]{y + 15, y + 15, y + 8}, 3);
        g.dispose();
    }
}
