/** The same angle helpers the server uses, so client and server agree on what a bearing means. */
public class GameMath {
    /** Wraps any angle into 0..360. */
    public static double norm(double a) {
        a %= 360;
        return a < 0 ? a + 360 : a;
    }

    /** Shortest signed difference a - b, in -180..180. */
    public static double angleDiff(double a, double b) {
        double d = norm(a - b);
        return d > 180 ? d - 360 : d;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
