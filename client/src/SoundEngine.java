import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * All of the game's audio, generated sample by sample. No .wav files anywhere.
 *
 * This runs on its OWN thread, which is the third producer-consumer pattern in the project:
 * the Swing thread PRODUCES one-shot sounds into a queue, and this thread CONSUMES them while
 * continuously mixing the background layers (engine hum, sea noise, enemy propellers).
 *
 * Why synthesise instead of loading samples? A depth charge is a noise burst with an exponential
 * decay; a propeller is a rhythmic thump whose rate is literally the target's RPM; an ASDIC ping
 * is a short tone. Those are three lines of maths each, they need no asset files, and the
 * propeller rate can follow the server's RPM value exactly rather than approximating it.
 */
public class SoundEngine implements Runnable {
    private static final float RATE = 22050f;
    private static final int CHUNK = 512;                  // samples per write: ~23 ms of audio

    private final ConcurrentLinkedQueue<OneShot> queue = new ConcurrentLinkedQueue<>();
    private final Random rnd = new Random();

    // live parameters, written by the UI thread and read by the audio thread
    private volatile double engineLevel = 0;               // own motors, 0..1
    private volatile double propRpm = 0;                   // enemy propellers heard on the hydrophone
    private volatile double propLevel = 0;
    private volatile double depth = 0;
    private volatile double asdic = 0;                     // escort ping loudness through the hull
    private volatile double screws = 0;                    // fast propellers passing overhead
    private double pingCountdown = 2, screwPhase;
    private volatile boolean alarm = false;
    private volatile boolean muted = false;
    private volatile boolean running = true;

    private double phase, propPhase, hullPhase, alarmPhase;
    private double creakCountdown = 4;

    /** A sound with a start time, mixed in until it finishes. */
    private static class OneShot {
        final String kind;
        double t;                                           // seconds elapsed
        final double len;
        final double gain;
        OneShot(String kind, double len, double gain) { this.kind = kind; this.len = len; this.gain = gain; }
    }

    // ---- called from the Swing thread ----

    public void setEngine(int speedOrder, boolean surfaced) {
        engineLevel = speedOrder == 0 ? 0 : (0.18 + 0.22 * speedOrder) * (surfaced ? 1.25 : 1.0);
    }

    public void setHydrophone(double rpm, double signal) {
        propRpm = rpm;
        propLevel = Math.max(0, Math.min(1, signal));
    }

    public void setDepth(double d) { depth = d; }

    /** The crew's only warning in realistic mode: pings that quicken as the escort closes. */
    public void setThreat(double asdicLevel, double screwsLevel) { asdic = asdicLevel; screws = screwsLevel; }

    public void setAlarm(boolean on) { alarm = on; }

    public void toggleMute() { muted = !muted; }

    public boolean isMuted() { return muted; }

    public void play(String kind) {
        switch (kind) {
            case "FIRE"      -> queue.add(new OneShot("fire", 1.2, 0.55));
            case "EXPLOSION" -> queue.add(new OneShot("boom", 2.6, 0.95));
            case "SPLASH"    -> queue.add(new OneShot("splash", 1.0, 0.5));
            case "PING"      -> queue.add(new OneShot("ping", 1.4, 0.45));
            case "CREAK"     -> queue.add(new OneShot("creak", 1.6, 0.4));
            case "CLANG"     -> queue.add(new OneShot("clang", 0.8, 0.4));
            default -> { }
        }
    }

    public void stop() { running = false; }

    // ---- the audio thread ----

    @Override
    public void run() {
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
            line.open(fmt, (int) RATE);                    // about 1 s of buffer
            line.start();
            byte[] buf = new byte[CHUNK * 2];

            while (running) {
                for (int i = 0; i < CHUNK; i++) {
                    double s = muted ? 0 : sample(1.0 / RATE);
                    short v = (short) (Math.max(-1, Math.min(1, s)) * 24000);
                    buf[i * 2] = (byte) (v & 0xFF);
                    buf[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                }
                line.write(buf, 0, buf.length);
            }
            line.drain();
        } catch (Exception e) {
            // No sound card, or audio is in use by something else. The game plays fine silently.
            System.out.println("[sound] disabled: " + e.getMessage());
        }
    }

    /** Produces one mixed sample and advances every oscillator by dt seconds. */
    private double sample(double dt) {
        double out = 0;

        // --- sea: filtered noise, always there ---
        out += (rnd.nextDouble() - 0.5) * 0.05;

        // --- own motors: a low hum plus its harmonic, pitch rising with power ---
        if (engineLevel > 0.001) {
            double f = 42 + engineLevel * 36;
            phase += 2 * Math.PI * f * dt;
            out += Math.sin(phase) * engineLevel * 0.22;
            out += Math.sin(phase * 2) * engineLevel * 0.07;
        }

        // --- enemy propellers: a thump per blade, so the rate IS the reported RPM ---
        if (propLevel > 0.02 && propRpm > 1) {
            double beatsPerSec = propRpm / 60.0;            // one thump per revolution, slow enough to count
            propPhase += beatsPerSec * dt;
            double frac = propPhase - Math.floor(propPhase);
            double thump = Math.exp(-frac * 9) * Math.sin(2 * Math.PI * 70 * frac);
            out += thump * propLevel * 0.45;
        }

        // --- ASDIC: the interval shortens as the range closes, exactly as the real sets did ---
        if (asdic > 0.03) {
            pingCountdown -= dt;
            if (pingCountdown <= 0) {
                pingCountdown = 0.8 + 2.8 * (1 - asdic);
                queue.add(new OneShot("ping", 1.4, 0.12 + 0.5 * asdic));
            }
        }

        // --- destroyer screws overhead: a fast swishing that every crew knew ---
        if (screws > 0.02) {
            screwPhase += dt;
            double swish = 0.5 + 0.5 * Math.sin(2 * Math.PI * 7.5 * screwPhase);
            out += (rnd.nextDouble() - 0.5) * swish * screws * 0.9;
        }

        // --- hull creak: more frequent the deeper she goes ---
        if (depth > 60) {
            creakCountdown -= dt * (1 + (depth - 60) / 90);
            if (creakCountdown <= 0) {
                creakCountdown = 2.5 + rnd.nextDouble() * 5;
                play("CREAK");
            }
        }

        // --- alarm klaxon ---
        if (alarm) {
            alarmPhase += dt;
            double w = (alarmPhase % 1.1) < 0.55 ? 1 : 0;
            out += Math.sin(2 * Math.PI * 420 * alarmPhase) * 0.10 * w;
        }

        // --- one-shots ---
        for (var it = queue.iterator(); it.hasNext(); ) {
            OneShot o = it.next();
            o.t += dt;
            if (o.t >= o.len) { it.remove(); continue; }
            out += voice(o) * o.gain;
        }
        return out;
    }

    private double voice(OneShot o) {
        double t = o.t, k = t / o.len;
        switch (o.kind) {
            case "fire": {
                // compressed air release: a hiss that fades
                double env = Math.exp(-t * 3.5);
                return (rnd.nextDouble() - 0.5) * env * 1.4;
            }
            case "boom": {
                // depth charge: a sharp crack, a deep thud, then the bubble rumble
                double crack = (rnd.nextDouble() - 0.5) * Math.exp(-t * 14) * 1.6;
                hullPhase += 1;
                double thud = Math.sin(2 * Math.PI * 38 * t) * Math.exp(-t * 2.2);
                double rumble = (rnd.nextDouble() - 0.5) * Math.exp(-t * 0.9) * 0.5;
                return crack + thud + rumble;
            }
            case "splash": {
                double env = Math.exp(-t * 4) * (1 - k);
                return (rnd.nextDouble() - 0.5) * env * 0.9;
            }
            case "ping": {
                // ASDIC: a short high tone, then its echo
                double tone = Math.sin(2 * Math.PI * 1150 * t) * Math.exp(-t * 7);
                double echo = t > 0.55 ? Math.sin(2 * Math.PI * 1100 * (t - 0.55)) * Math.exp(-(t - 0.55) * 9) * 0.5 : 0;
                return tone + echo;
            }
            case "creak": {
                // steel under pressure: a groan that slides in pitch
                double f = 95 + 45 * Math.sin(t * 2.1);
                return Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 1.6) * (0.5 + 0.5 * Math.sin(t * 11));
            }
            case "clang": {
                double f = 540;
                return (Math.sin(2 * Math.PI * f * t) + 0.6 * Math.sin(2 * Math.PI * f * 1.51 * t))
                        * Math.exp(-t * 6);
            }
            default:
                return 0;
        }
    }
}
