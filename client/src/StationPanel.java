import javax.swing.JPanel;

/** Everything a station screen must be able to do. */
public abstract class StationPanel extends JPanel {
    protected final NetworkClient net;

    protected StationPanel(NetworkClient net) {
        this.net = net;
        setBackground(Theme.STEEL_DARK);
    }

    /** Called on the Swing thread whenever a new STATE arrives. */
    public abstract void apply(Snapshot s);

    /** Called ~30 times a second by the repaint timer: animate needles, send held-key commands. */
    public abstract void tick();

    public abstract String title();
}
