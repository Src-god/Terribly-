package org.telegram.messenger.gramify;

/**
 * Gramify CallMixer — apna music call ke ANDAR bhejne ka engine.
 *
 * <h3>Ye kaam kaise karta hai</h3>
 * <pre>
 *   MusicDecoder (MP4/AAC decode)  --push-->  [ CallMixer ring buffer ]  --mix-->  outgoing mic PCM
 *                        |                                                              |
 *                        +--> AudioTrack (aap bhi sunte ho)                            v
 *                                                                        saamne wala bhi sunta hai
 * </pre>
 *
 * Matlab: gaana jab call me chal raha ho, to jo PCM mic ke saath mix hoke
 * outgoing stream me jaata hai — saamne wala bhi sunta hai, aur aap bhi (AudioTrack se).
 *
 * Pure Java (koi Android import nahi) — isliye desktop JVM par test ho jata hai
 * (dekho: test/CallMixerTest.java).
 *
 * Threading: ek producer (decoder thread) + ek consumer (audio thread).
 * Sirf apne-apne index advance hote hain, isliye lock ki zaroorat nahi.
 */
public final class CallMixer {

    /** Buffer ka internal sample rate (Telegram calls 48 kHz mono use karti hain). */
    public static final int RATE = 48000;
    /** Ring buffer = 6 second (kam latency, network jitter absorb kar leta hai). */
    private static final int CAPACITY = RATE * 6;

    private static final short[] ring = new short[CAPACITY];
    private static volatile int writePos = 0;
    private static volatile int readPos = 0;

    private static volatile boolean active = false;
    private static volatile float volume = 0.75f;   // music injection level (0..1.5)
    private static volatile float duck = 0.5f;      // music bajte waqt mic kitna dabaye (0..1)

    /** phase accumulator — 48k -> call rate resampling ke liye (lineare interpolation) */
    private static double phase = 0.0;

    /** stats */
    private static volatile long mixedFrames = 0;
    private static volatile long droppedFrames = 0;

    private CallMixer() {}

    /* ------------------------------------------------------------------ *
     *  config
     * ------------------------------------------------------------------ */

    public static void setActive(boolean v) {
        if (!v) {
            phase = 0.0;
        }
        active = v;
    }

    public static boolean isActive() { return active; }

    public static void setVolume(float v) { volume = v < 0f ? 0f : (v > 1.5f ? 1.5f : v); }

    public static float getVolume() { return volume; }

    public static void setDuck(float v) { duck = v < 0f ? 0f : (v > 1f ? 1f : v); }

    public static float getDuck() { return duck; }

    public static long getMixedFrames() { return mixedFrames; }

    public static long getDroppedFrames() { return droppedFrames; }

    public static void resetStats() { mixedFrames = 0; droppedFrames = 0; }

    /* ------------------------------------------------------------------ *
     *  producer side (MusicDecoder thread)
     * ------------------------------------------------------------------ */

    /** kitne frames bhare hue hain */
    public static int availableFrames() {
        int w = writePos, r = readPos;
        if (w >= r) return w - r;
        return CAPACITY - r + w;
    }

    public static int freeFrames() { return CAPACITY - 1 - availableFrames(); }

    /**
     * Decoded PCM (mono, {@link #RATE} Hz) ring me daalo.
     * @return jitne frames likh paaye
     */
    public static synchronized int write(short[] pcm, int offset, int frames) {
        if (pcm == null || frames <= 0) return 0;
        int free = freeFrames();
        if (free <= 0) {
            droppedFrames += frames;
            return 0;
        }
        final int n = Math.min(frames, free);
        int w = writePos;
        for (int i = 0; i < n; i++) {
            ring[w] = pcm[offset + i];
            w++;
            if (w == CAPACITY) w = 0;
        }
        writePos = w;
        if (n < frames) droppedFrames += (frames - n);
        return n;
    }

    public static synchronized void clear() {
        readPos = 0;
        writePos = 0;
        phase = 0.0;
    }

    /* ------------------------------------------------------------------ *
     *  consumer side (audio thread — VoiceEnhancer se call hota hai)
     * ------------------------------------------------------------------ */

    /**
     * Music ko outgoing mic PCM me mix karta hai (in place).
     *
     * @param pcm       mic samples (16-bit, interleaved)
     * @param off       first sample index
     * @param count     total shorts (frames * channels)
     * @param sampleRate call ka sample rate (44100 / 48000)
     * @param channels  1 (mono) ya 2
     * @return true agar kuch mix hua
     */
    public static boolean mixInto(short[] pcm, int off, int count, int sampleRate, int channels) {
        if (!active || pcm == null || count <= 0) return false;
        if (sampleRate <= 0) sampleRate = RATE;
        if (channels <= 0) channels = 1;

        final int frames = count / channels;
        final int avail = availableFrames();
        if (avail < 2) return false;

        final double step = (double) RATE / (double) sampleRate;  // 48k -> call rate
        final float vol = volume;
        int r = readPos;
        int consumed = 0;
        boolean any = false;

        for (int f = 0; f < frames; f++) {
            // linear interpolation
            final int i0 = (r + (int) phase) % CAPACITY;
            final double frac = phase - (int) phase;
            final int i1 = (i0 + 1) % CAPACITY;
            final float music = (float) ((1.0 - frac) * ring[i0] + frac * ring[i1]);

            // kitna aage badhe
            phase += step;
            final int advance = (int) phase;
            if (advance > 0) {
                phase -= advance;
                r = (r + advance) % CAPACITY;
                consumed += advance;
            }

            if (avail - consumed < 3) break;   // data khatam — chup-chaap ruk jao

            final float m = music * vol;

            if (channels == 1) {
                final int idx = off + f;
                final int mic = pcm[idx];
                pcm[idx] = clamp16(mic + m);
            } else {
                final int idx = off + f * 2;
                final int micL = pcm[idx];
                final int micR = pcm[idx + 1];
                pcm[idx] = clamp16(micL + m);
                pcm[idx + 1] = clamp16(micR + m);
            }
            any = true;
        }

        readPos = r;
        if (any) mixedFrames += frames;

        // ---- ducking: music chal raha hai to mic thoda dabao ----
        if (any && duck > 0.001f && vol > 0.001f) {
            final float g = 1f - duck * Math.min(1f, vol);
            for (int i = 0; i < count; i++) {
                final int idx = off + i;
                pcm[idx] = (short) (pcm[idx] * g);
            }
        }
        return any;
    }

    private static short clamp16(float v) {
        if (v > 32767f) return 32767;
        if (v < -32768f) return -32768;
        return (short) v;
    }
}
