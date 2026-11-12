import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Optional art and music slots.
 *
 * Every picture is looked up as assets/<name> (next to where you run the game, or one folder up).
 * If the file is missing the game draws the built-in version instead, so the game always runs and
 * you can drop Blender renders in one at a time.
 */
public class Assets {
    private static final Map<String, BufferedImage> cache = new HashMap<>();
    private static final BufferedImage MISSING = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    /** @return the file if it exists, else null */
    public static File file(String name) {
        for (String root : new String[]{"assets/", "../assets/"}) {
            File f = new File(root + name);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** @return the image, or null when there is no such file */
    public static synchronized BufferedImage image(String name) {
        BufferedImage img = cache.get(name);
        if (img == null) {
            try {
                File f = file(name);
                img = f == null ? MISSING : ImageIO.read(f);
            } catch (Exception e) {
                img = MISSING;
            }
            if (img == null) img = MISSING;
            cache.put(name, img);
        }
        return img == MISSING ? null : img;
    }
}
