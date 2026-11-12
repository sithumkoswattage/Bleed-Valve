import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;

/**
 * The Gruppenhorchgerät: a brass wheel that steers an underwater listening array.
 *
 * The server does the acoustics (angle difference, distance attenuation) and sends back
 * one signal strength and one propeller RPM. This widget just shows them, which is why
 * the "physics" lives on the server where every client agrees on it.
 */
public class HydrophoneDial extends JComponent {
    private double bearing;        // where the operator is listening
    private double signal;         // 0..1 from the server
    private double shownSignal;
    private double rpm;
    private double sweep;
    private boolean training;
    private double beatPhase, beatPulse;
    private final java.util.ArrayDeque<Long> taps = new java.util.ArrayDeque<>();
    private double counted;

    /**
     * The operator taps in time with the propeller beats, and the stopwatch gives the RPM.
     * That, divided by the class's revolutions-per-knot from the manual, is the target's speed.
     */
    public void tap() {
        long now = System.currentTimeMillis();
        if (!taps.isEmpty() && now - taps.peekLast() > 3000) taps.clear();
        taps.addLast(now);
        while (taps.size() > 7) taps.removeFirst();
        if (taps.size() >= 3) counted = 60000.0 * (taps.size() - 1) / (taps.peekLast() - taps.peekFirst());
    }

    public HydrophoneDial() {
        setPreferredSize(new Dimension(260, 260));
        setOpaque(false);
    }

    public void update(double bearing, double signal, double rpm, boolean training) {
        this.training = training;
        this.bearing = bearing;
        this.signal = signal;
        this.rpm = rpm;
    }

    public void tick() {
        shownSignal += (signal - shownSignal) * 0.15;
        sweep += 0.04;
        // The needle kicks once per propeller revolution, in step with the sound.
        if (rpm > 1 && signal > 0.05) {
            double before = Math.floor(beatPhase);
            beatPhase += rpm / 60.0 * 0.033;
            if (Math.floor(beatPhase) != before) beatPulse = 1;
        }
        beatPulse *= 0.80;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.smooth((Graphics2D) g0.create());
        int w = getWidth(), h = getHeight();
        int d = Math.min(w, h) - 40;
        int cx = w / 2, cy = (h - 26) / 2;
        double r = d / 2.0;

        g.setColor(new Color(0x08, 0x12, 0x0C));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));

        // range rings, like a sonar scope
        g.setColor(new Color(0x1B, 0x3A, 0x28));
        g.setStroke(new BasicStroke(1f));
        for (int i = 1; i <= 3; i++) {
            double rr = r * i / 3.0;
            g.draw(new Ellipse2D.Double(cx - rr, cy - rr, rr * 2, rr * 2));
        }
        for (int b = 0; b < 360; b += 30) {
            double a = Math.toRadians(90 - b);
            g.drawLine(cx, cy, (int) (cx + Math.cos(a) * r), (int) (cy - Math.sin(a) * r));
        }

        // the listening cone: wide and faint, because a hydrophone hears a sector, not a point
        double a = Math.toRadians(90 - bearing);
        int alpha = (int) (40 + 150 * shownSignal);
        g.setColor(new Color(0x4F, 0xE0, 0x8A, Math.min(200, alpha)));
        g.fillArc((int) (cx - r), (int) (cy - r), d, d, (int) Math.toDegrees(a) - 11, 22);

        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, 0));
        g.setColor(Theme.PHOSPHOR);
        g.drawLine(cx, cy, (int) (cx + Math.cos(a) * r), (int) (cy - Math.sin(a) * r));

        g.setColor(Theme.BRASS);
        g.setStroke(new BasicStroke(3f));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, d, d));
        g.fillOval(cx - 5, cy - 5, 10, 10);

        g.setFont(Theme.SMALL);
        g.setColor(Theme.AMBER_DIM);
        for (int b = 0; b < 360; b += 90) {
            double ta = Math.toRadians(90 - b);
            String t = b == 0 ? "N" : b == 90 ? "E" : b == 180 ? "S" : "W";
            g.drawString(t, (int) (cx + Math.cos(ta) * (r + 12)) - 4, (int) (cy - Math.sin(ta) * (r + 12)) + 4);
        }

        // readouts
        g.setFont(Theme.LABEL);
        g.setColor(Theme.INK);
        g.drawString(String.format("LISTENING %03d", Math.round(bearing) % 360), 6, h - 28);
        g.setColor(shownSignal > 0.55 ? Theme.PHOSPHOR : Theme.AMBER_DIM);
        String count = counted > 0 ? String.format("count %d RPM", Math.round(counted)) : "T = tap the beat";
        g.drawString(training && rpm > 1 ? count + String.format("  (true %d)", Math.round(rpm)) : count, 6, h - 12);

        // signal strength meter
        int bw = 90, bx = w - bw - 6, by = h - 22;
        g.setColor(new Color(0x10, 0x16, 0x12));
        g.fillRect(bx, by, bw, 10);
        g.setColor(Theme.level(shownSignal));
        g.fillRect(bx, by, (int) (bw * Math.min(1, shownSignal * (1 + 0.25 * beatPulse))), 10);
        g.setColor(Theme.STEEL_EDGE);
        g.drawRect(bx, by, bw, 10);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.AMBER_DIM);
        g.drawString("SIGNAL", bx, by - 3);
        g.dispose();
    }
}
