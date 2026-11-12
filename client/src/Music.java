import java.io.ByteArrayInputStream;
import java.io.File;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * Win and lose music.
 *
 * If assets/music/<name>.wav exists it is played (drop your own track in and it replaces ours).
 * Otherwise a short theme is synthesised from scratch: additive "brass", bells, pads and timpani,
 * run through a simple comb-filter reverb. Java's built-in audio only reads WAV, not mp3.
 */
public class Music {
    private static final float RATE = 22050f;
    private static Clip clip;

    /** Plays "win" or "lose". Opening a Clip can block briefly, so it happens off the Swing thread. */
    public static void play(String name, boolean muted) {
        stop();
        if (muted) return;
        Thread t = new Thread(() -> {
            try {
                File f = Assets.file("music/" + name + ".wav");
                AudioInputStream in = f != null ? AudioSystem.getAudioInputStream(f) : synth(name);
                Clip c = AudioSystem.getClip();
                c.open(in);
                synchronized (Music.class) { clip = c; }
                c.start();
            } catch (Exception | Error e) {
                // no audio device (a server, a headless test): silently carry on
            }
        }, "Music");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        if (clip != null) {
            try { clip.stop(); clip.close(); } catch (Exception ignored) { }
            clip = null;
        }
    }

    // ---------------------------------------------------------------- synthesis

    private static AudioInputStream synth(String name) {
        float[] buf = new float[(int) (RATE * (name.equals("win") ? 17 : 19))];
        if (name.equals("win")) composeWin(buf); else composeLose(buf);
        reverb(buf);
        normalise(buf, 0.8f);
        byte[] pcm = new byte[buf.length * 2];
        for (int i = 0; i < buf.length; i++) {
            short v = (short) Math.max(-32767, Math.min(32767, buf[i] * 32767));
            pcm[2 * i] = (byte) (v & 0xFF);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        return new AudioInputStream(new ByteArrayInputStream(pcm), fmt, buf.length);
    }

    /** Slow, heavy and minor: a low drone, descending bell notes, a distant timpani heartbeat. */
    private static void composeLose(float[] b) {
        pad(b, 0, 19, 45, 0.30);                      // A1 drone for the whole piece
        pad(b, 0.5, 8, 57, 0.16);  pad(b, 0.5, 8, 60, 0.12);    // A minor swell
        pad(b, 8.5, 9.5, 53, 0.16); pad(b, 8.5, 9.5, 56, 0.12); // F / Ab colour, darker
        int[] melody = {76, 74, 72, 71, 69, 67, 64, 57};         // E5 D5 C5 B4 A4 G4 E4 A3
        double[] at = {1.0, 3.0, 5.0, 7.0, 9.5, 11.5, 13.5, 16.0};
        for (int i = 0; i < melody.length; i++) bell(b, at[i], 3.5, melody[i], 0.22);
        for (double t = 2; t < 17; t += 2.5) timp(b, t, 0.5);
    }

    /** Rising and bright: a three-note call, a rising answer, then a full held chord with timpani. */
    private static void composeWin(float[] b) {
        timp(b, 0.0, 0.7); timp(b, 0.5, 0.5);
        brass(b, 1.0, 0.5, 60, 0.30); brass(b, 1.5, 0.5, 67, 0.30); brass(b, 2.0, 1.4, 72, 0.34);   // C G C
        brass(b, 3.8, 0.5, 64, 0.30); brass(b, 4.3, 0.5, 67, 0.30); brass(b, 4.8, 1.4, 76, 0.34);   // E G E
        brass(b, 6.6, 0.4, 72, 0.30); brass(b, 7.0, 0.4, 74, 0.30); brass(b, 7.4, 0.4, 76, 0.30);
        brass(b, 7.8, 1.6, 79, 0.36);                                                               // G5 high
        pad(b, 9.6, 7.4, 48, 0.28); pad(b, 9.6, 7.4, 60, 0.20); pad(b, 9.6, 7.4, 64, 0.18);
        pad(b, 9.6, 7.4, 67, 0.18); pad(b, 9.6, 7.4, 72, 0.16);                                      // C major, held
        brass(b, 9.6, 5.5, 72, 0.30); brass(b, 9.6, 5.5, 76, 0.22); brass(b, 9.6, 5.5, 79, 0.22);
        timp(b, 9.6, 0.9); timp(b, 11.6, 0.5); timp(b, 13.6, 0.5);
        bell(b, 10.5, 5, 84, 0.12); bell(b, 12.0, 5, 91, 0.08);
    }

    private static double hz(int midi) { return 440.0 * Math.pow(2, (midi - 69) / 12.0); }

    /** Sustained chord tone: a few soft partials, slow attack and release. */
    private static void pad(float[] b, double start, double dur, int midi, double amp) {
        double f = hz(midi);
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            double t = i / RATE;
            double env = Math.min(1, t / 1.8) * Math.min(1, (dur - t) / 2.2);
            double v = Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 2.003 * t)
                     + 0.25 * Math.sin(2 * Math.PI * f * 0.997 * t);
            b[s0 + i] += (float) (v * env * amp * 0.6);
        }
    }

    /** Brass: harmonics falling off as 1/n, a fast attack and a gentle release. */
    private static void brass(float[] b, double start, double dur, int midi, double amp) {
        double f = hz(midi);
        int s0 = (int) (start * RATE), n = (int) ((dur + 0.3) * RATE);
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            double t = i / RATE;
            double env = Math.min(1, t / 0.06) * (t < dur ? 1 : Math.max(0, 1 - (t - dur) / 0.3));
            double vib = 1 + 0.004 * Math.sin(2 * Math.PI * 5.2 * t);
            double v = 0;
            for (int h = 1; h <= 7; h++) v += Math.sin(2 * Math.PI * f * vib * h * t) / h;
            b[s0 + i] += (float) (v * env * amp * 0.55);
        }
    }

    /** Bell: inharmonic partials with exponential decay. */
    private static void bell(float[] b, double start, double dur, int midi, double amp) {
        double f = hz(midi);
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        double[] ratio = {1, 2.76, 5.4, 8.9}, gain = {1, 0.5, 0.25, 0.12};
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            double t = i / RATE, v = 0;
            for (int k = 0; k < 4; k++) v += gain[k] * Math.sin(2 * Math.PI * f * ratio[k] * t) * Math.exp(-t * (1.2 + k * 1.1));
            b[s0 + i] += (float) (v * amp);
        }
    }

    /** Timpani: a sine that sags in pitch as it decays. */
    private static void timp(float[] b, double start, double amp) {
        int s0 = (int) (start * RATE), n = (int) (1.4 * RATE);
        double phase = 0;
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            double t = i / RATE;
            double f = 52 + 38 * Math.exp(-t * 9);
            phase += 2 * Math.PI * f / RATE;
            b[s0 + i] += (float) (Math.sin(phase) * Math.exp(-t * 3.2) * amp);
        }
    }

    /** Two feedback comb filters: a cheap hall. */
    private static void reverb(float[] b) {
        int[] delay = {(int) (0.37 * RATE), (int) (0.53 * RATE)};
        float[] fb = {0.42f, 0.36f};
        float[] out = b.clone();
        for (int d = 0; d < 2; d++) {
            float[] y = new float[b.length];
            for (int i = 0; i < b.length; i++) {
                y[i] = b[i] + (i >= delay[d] ? y[i - delay[d]] * fb[d] : 0);
            }
            for (int i = 0; i < b.length; i++) out[i] += 0.35f * (y[i] - b[i]);
        }
        System.arraycopy(out, 0, b, 0, b.length);
        // fade the last second so the clip never ends on a click
        int fade = (int) RATE;
        for (int i = 0; i < fade; i++) b[b.length - 1 - i] *= (float) i / fade;
    }

    private static void normalise(float[] b, float peak) {
        float max = 0;
        for (float v : b) max = Math.max(max, Math.abs(v));
        if (max < 1e-6f) return;
        float k = peak / max;
        for (int i = 0; i < b.length; i++) b[i] *= k;
    }
}
