import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
import javax.swing.JComponent;

/**
 * The damage board: four compartments, bow to stern, each with integrity and water level.
 *
 * Click a compartment to send the damage-control party there. There is only one party, so a
 * second order waits in a queue on the server — the engineer has to decide what to save first.
 */
public class DamageBoard extends JComponent {
    private static final String[] NAMES = {"BOW ROOM", "CONTROL", "ENGINE", "AFT ROOM"};
    private static final String[] EFFECT = {"torpedo tubes", "dive planes", "motor power", "trim & stores"};

    private final double[] health = {100, 100, 100, 100};
    private final double[] flood = {0, 0, 0, 0};
    private int repairing = -1;
    private int hover = -1;
    private boolean training;

    private static String holeWord(double h) {
        return h >= 100 ? "sound" : h >= 75 ? "leaking" : h >= 50 ? "holed" : h >= 25 ? "badly holed" : "wrecked";
    }

    private static String waterWord(double f) {
        return f <= 0 ? "dry" : f <= 25 ? "ankle-deep" : f <= 50 ? "knee-deep" : f <= 75 ? "waist-deep" : "flooded";
    }

    public DamageBoard(IntConsumer onRepairOrdered) {
        setPreferredSize(new Dimension(280, 204));
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int row = rowAt(e.getY());
                if (row >= 0) onRepairOrdered.accept(row);
            }
            @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = rowAt(e.getY());
                if (row != hover) { hover = row; repaint(); }
            }
        });
    }

    private static final int ROW_H = 43;

    private int rowAt(int y) {
        int row = (y - 26) / ROW_H;
        return (row >= 0 && row < 4) ? row : -1;
    }

    /** Server sends these as compact comma strings, e.g. "100,65,80,100". */
    public void update(String healthCsv, String floodCsv, int repairing, boolean training) {
        parse(healthCsv, health);
        parse(floodCsv, flood);
        this.repairing = repairing;
        this.training = training;
    }

    private void parse(String csv, double[] into) {
        if (csv == null || csv.isEmpty()) return;
        String[] p = csv.split(",");
        for (int i = 0; i < Math.min(p.length, into.length); i++) {
            try { into[i] = Double.parseDouble(p[i].trim()); } catch (NumberFormatException ignored) { }
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        Theme.plate(g, 0, 0, getWidth() - 1, getHeight() - 1);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.AMBER_DIM);
        g.drawString("DAMAGE REPORTS   (click to send the party)", 10, 16);

        int barW = getWidth() - 112;
        for (int i = 0; i < 4; i++) {
            int y = 26 + i * ROW_H;
            boolean busy = repairing == i;
            if (hover == i) {
                g.setColor(new Color(0xFF, 0xB6, 0x42, 22));
                g.fillRect(4, y, getWidth() - 9, ROW_H - 3);
            }

            g.setFont(Theme.LABEL);
            g.setColor(busy ? Theme.PHOSPHOR : Theme.INK);
            g.drawString(NAMES[i], 10, y + 12);

            g.setFont(Theme.SMALL);
            if (busy) {
                g.setColor(Theme.PHOSPHOR);
                g.drawString("party working", 92, y + 12);
            } else {
                g.setColor(Theme.STEEL_EDGE);
                g.drawString(EFFECT[i], 92, y + 12);
            }

            // integrity, with its figure on the same line as its bar
            bar(g, 10, y + 18, barW, 7, health[i] / 100.0, Theme.level(health[i] / 100.0));
            g.setColor(Theme.INK);
            g.drawString(training ? Math.round(health[i]) + "%" : holeWord(health[i]), barW + 18, y + 25);

            // water level, red as it rises
            bar(g, 10, y + 30, barW, 6, flood[i] / 100.0,
                flood[i] > 1 ? Theme.blend(Theme.AMBER, Theme.ALERT, Math.min(1, flood[i] / 70)) : Theme.STEEL_EDGE);
            g.setColor(flood[i] > 40 ? Theme.ALERT : Theme.AMBER_DIM);
            g.drawString(training ? Math.round(flood[i]) + "% sea" : waterWord(flood[i]), barW + 18, y + 37);
        }
        g.dispose();
    }

    private void bar(Graphics2D g, int x, int y, int w, int h, double frac, Color c) {
        g.setColor(new Color(0x10, 0x16, 0x12));
        g.fillRect(x, y, w, h);
        g.setColor(c);
        g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, frac))), h);
        g.setColor(Theme.STEEL_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x, y, w, h);
    }
}
