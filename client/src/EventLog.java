import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.JComponent;

/** The crew message strip: the last few EVENT and ERROR lines from the server. */
public class EventLog extends JComponent {
    private static final int MAX = 7;
    private final Deque<Entry> lines = new ArrayDeque<>();

    private record Entry(String text, Color colour, long at) { }

    public EventLog() {
        setPreferredSize(new Dimension(300, 130));
        setOpaque(false);
    }

    public void add(String text, Color colour) {
        lines.addFirst(new Entry(text, colour, System.currentTimeMillis()));
        while (lines.size() > MAX) lines.removeLast();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        Theme.plate(g, 0, 0, getWidth() - 1, getHeight() - 1);
        g.setFont(Theme.LABEL);
        g.setColor(Theme.AMBER_DIM);
        g.drawString("CREW LOG", 10, 16);

        g.setFont(Theme.SMALL);
        int y = 34;
        long now = System.currentTimeMillis();
        for (Entry e : lines) {
            // older lines fade out, so the newest is always the easiest to read
            float age = Math.min(1f, (now - e.at()) / 12000f);
            Color c = Theme.blend(Theme.STEEL_EDGE, e.colour(), 1 - age * 0.65);
            g.setColor(c);
            String t = e.text();
            while (g.getFontMetrics().stringWidth(t) > getWidth() - 20 && t.length() > 4) {
                t = t.substring(0, t.length() - 2);
            }
            g.drawString(t, 10, y);
            y += 14;
        }
        g.dispose();
    }
}
