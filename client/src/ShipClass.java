import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;

/**
 * The recognition manual's data, and the silhouettes that go with it.
 *
 * The same drawing code serves the manual page and the periscope, so a ship in the optics
 * looks exactly like its entry in the book. Identifying her is the player's job: pick the
 * wrong class and the stadimeter uses the wrong mast height, so the range comes out wrong.
 */
public class ShipClass {
    public static final String[] NAME = {"Large freighter", "Medium freighter", "Small freighter", "Destroyer"};
    public static final String[] NOTE = {"7,800 t  two masts, kingposts", "5,200 t  two masts",
                                         "4,100 t  engines aft, one mast", "escort  two funnels"};
    public static final double[] MAST = {30, 24, 18, 22};            // masthead height, metres
    public static final double[] LENGTH = {140, 120, 90, 100};       // metres
    public static final double[] RPM_PER_KNOT = {12, 14, 17, 16};

    /**
     * Draws a silhouette standing on baseY.
     * @param w apparent length in pixels (already foreshortened)
     * @param h masthead height in pixels
     * @param dir +1 bow to the right, -1 bow to the left
     */
    public static void draw(Graphics2D g, int cls, double cx, double baseY, double w, double h, int dir, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke((float) Math.max(1, h * 0.035)));
        double hull = h * (cls == 3 ? 0.13 : 0.2);
        rect(g, cx, baseY, w, dir, -0.5, 0.5, 0, hull);
        // raked bow
        int bx = (int) (cx + dir * w * 0.5);
        g.fillPolygon(new int[]{bx, (int) (bx + dir * Math.max(1, w * 0.05)), bx},
                      new int[]{(int) baseY, (int) (baseY - hull), (int) (baseY - hull)}, 3);
        switch (cls) {
            case 0 -> {
                rect(g, cx, baseY, w, dir, -0.08, 0.10, hull, h * 0.45);
                rect(g, cx, baseY, w, dir, -0.02, 0.04, hull, h * 0.64);
                mast(g, cx, baseY, w, dir, -0.30, h); mast(g, cx, baseY, w, dir, 0.30, h);
                mast(g, cx, baseY, w, dir, -0.18, h * 0.6); mast(g, cx, baseY, w, dir, 0.18, h * 0.6);
            }
            case 1 -> {
                rect(g, cx, baseY, w, dir, -0.10, 0.08, hull, h * 0.42);
                rect(g, cx, baseY, w, dir, -0.04, 0.02, hull, h * 0.62);
                mast(g, cx, baseY, w, dir, -0.32, h); mast(g, cx, baseY, w, dir, 0.28, h);
            }
            case 2 -> {
                rect(g, cx, baseY, w, dir, -0.44, -0.24, hull, h * 0.42);
                rect(g, cx, baseY, w, dir, -0.38, -0.31, hull, h * 0.62);
                mast(g, cx, baseY, w, dir, 0.12, h); mast(g, cx, baseY, w, dir, 0.36, h * 0.5);
            }
            default -> {
                rect(g, cx, baseY, w, dir, 0.10, 0.24, hull, h * 0.44);
                rect(g, cx, baseY, w, dir, -0.06, 0.02, hull, h * 0.52);
                rect(g, cx, baseY, w, dir, -0.20, -0.12, hull, h * 0.48);
                mast(g, cx, baseY, w, dir, 0.17, h);
                rect(g, cx, baseY, w, dir, 0.32, 0.40, hull, h * 0.24);
                rect(g, cx, baseY, w, dir, -0.42, -0.34, hull, h * 0.24);
            }
        }
    }

    private static void rect(Graphics2D g, double cx, double baseY, double w, int dir,
                             double f0, double f1, double y0, double y1) {
        double xa = cx + dir * w * f0, xb = cx + dir * w * f1;
        int x = (int) Math.round(Math.min(xa, xb));
        int ww = (int) Math.max(1, Math.round(Math.abs(xb - xa)));
        g.fillRect(x, (int) Math.round(baseY - y1), ww, (int) Math.max(1, Math.round(y1 - y0)));
    }

    private static void mast(Graphics2D g, double cx, double baseY, double w, int dir, double f, double h) {
        int x = (int) Math.round(cx + dir * w * f);
        g.drawLine(x, (int) baseY, x, (int) Math.round(baseY - h));
    }
}
