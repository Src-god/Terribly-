package org.telegram.messenger.gramify;

/**
 * Gramify VoiceDSP — outgoing voice processor.
 *
 * Pure Java. ZERO Android dependency, so it can be unit-tested on a desktop JVM
 * (see gramify/test/VoiceDspTest.java).
 *
 * Signal chain (order matters):
 *
 *   PCM16 in
 *     -> input gain (mic boost)
 *     -> high-pass 90 Hz (rumble / AC hum / table thump hataata hai)
 *     -> 5-band peaking EQ        (100 / 300 / 1200 / 3500 / 8000 Hz)
 *     -> low shelf (bass) + high shelf (treble)   <- "Equaliser + Booster"
 *     -> noise gate               (typing / fan / breath noise dabata hai)
 *     -> compressor (soft knee)   (awaaz ko consistent rakhta hai)
 *     -> output gain (booster)
 *     -> brick-wall limiter (-0.5 dBFS, clipping = distortion avoid)
 *     -> dry/wet mix (amount)
 *   PCM16 out
 *
 * Everything is in-place on a short[] so it costs ~0 allocations per audio frame.
 */
public final class VoiceDsp {

    /* ------------------------------------------------------------------ *
     *  Presets
     * ------------------------------------------------------------------ */

    public static final int PRESET_OFF         = 0;
    public static final int PRESET_VOICE_BOOST = 1; // clear voice / calls
    public static final int PRESET_STUDIO      = 2; // podcast / voice notes
    public static final int PRESET_BASS        = 3; // deep, chesty voice
    public static final int PRESET_BRIGHT      = 4; // crisp, airy voice
    public static final int PRESET_LOUD        = 5; // maximum loudness/booster
    public static final int PRESET_CLEAR_BOOST = 6; // "Clear boost"  (reference kit)
    public static final int PRESET_SAFE        = 7; // "Safe"        (reference kit)
    public static final int PRESET_ROYAL       = 8; // "Royal"       (reference kit)
    public static final int PRESET_CUSTOM      = 9; // user ke sliders
    public static final int PRESET_LORD        = 10; // "Lord"       (reference kit)
    public static final int PRESET_MAX_POWER   = 11; // MAX POWER — full blast (phone-phat)
    public static final int PRESET_COUNT       = 12;

    public static final int BAND_COUNT = 5;
    /** Centre frequencies (Hz) of the 5 bands. */
    public static final float[] BAND_FREQ = {100f, 300f, 1200f, 3500f, 8000f};
    public static final String[] BAND_NAME = {"100 Hz", "300 Hz", "1.2 kHz", "3.5 kHz", "8 kHz"};
    public static final String[] PRESET_NAME = {
            "Off", "Clear Voice", "Studio", "Deep Bass", "Bright", "Loud Booster",
            "Clear Boost", "Safe", "Royal", "Custom", "Lord", "MAX POWER"
    };
    /** UI me dikhne wali chhoti description (kit ke mode samajhne ke liye). */
    public static final String[] PRESET_DESC = {
            "koi processing nahi",
            "+6 dB, presence peak — calls ke liye saaf",
            "broadcast/EQ shaping",
            "deep, chesty",
            "crisp + airy",
            "+12 dB + heavy compression",
            "reference 'Clear boost' — +18 dB, halka drive",
            "reference 'Safe' — +12 dB, soft",
            "reference 'Royal' — +24 dB, drive 0.18, sustain",
            "aapke sliders",
            "reference 'Lord' — +30 dB, drive 0.35, heavy sustain",
            "FULL BLAST — +36 dB + 4x loudness + limiter (sabse tez)"
    };

    private static final float DB = 1f; // readability helper for comments only

    /* ------------------------------------------------------------------ *
     *  Settings (serialisable by the Android layer)
     * ------------------------------------------------------------------ */

    public static final class Settings implements Cloneable {
        public boolean enabled = false;
        public int preset = PRESET_VOICE_BOOST;
        /** Dry/wet: 0 = bilkul original, 1 = pura processed. */
        public float amount = 1f;
        /** Mic boost in dB (0..24). */
        public float inputGainDb = 9f;
        /** Final booster in dB (-12..18). */
        public float outputGainDb = 3f;
        /** 5 peaking bands in dB (-12..12). */
        public final float[] bandDb = new float[BAND_COUNT];
        /** Low shelf / high shelf in dB (-12..12). */
        public float bassDb = 0f;
        public float trebleDb = 0f;
        public boolean highPass = true;
        public boolean noiseGate = true;
        public float gateThresholdDb = -55f;
        public boolean compressor = true;
        public float compThresholdDb = -18f;
        public float compRatio = 3f;
        public boolean limiter = true;
        /** Extra loudness multiplier — 1.0 = off, 8x tak (reference ka "maxBoost"). */
        public float loudness = 1f;
        /** Saturation / drive 0..1 — awaaz garm aur "aage" lagti hai. */
        public float drive = 0f;
        /** Presence peak 3.2 kHz (dB). */
        public float presenceDb = 0f;
        /** Doosra aggressive compressor (reference ka comp2: -10 dB, 12:1). */
        public boolean comp2 = false;
        /** Auto-sustain (AGC) — halki awaaz ko upar uthata hai. */
        public boolean sustain = false;
        public float sustainTargetDb = -12f;
        public float sustainMaxGainDb = 6f;
        /** Limiter ceiling dBFS (-6..0). 0 se kam rakhna = zyada tez par distortion kam. */
        public float limiterDb = -0.5f;

        public Settings clone() {
            final Settings s = new Settings();
            s.enabled = enabled;
            s.preset = preset;
            s.amount = amount;
            s.inputGainDb = inputGainDb;
            s.outputGainDb = outputGainDb;
            System.arraycopy(bandDb, 0, s.bandDb, 0, BAND_COUNT);
            s.bassDb = bassDb;
            s.trebleDb = trebleDb;
            s.highPass = highPass;
            s.noiseGate = noiseGate;
            s.gateThresholdDb = gateThresholdDb;
            s.compressor = compressor;
            s.compThresholdDb = compThresholdDb;
            s.compRatio = compRatio;
            s.limiter = limiter;
            return s;
        }
    }

    /** Factory presets ko ek Settings object me daalta hai. */
    public static void applyPreset(Settings s, int preset) {
        s.preset = preset;
        s.highPass = true;
        s.noiseGate = true;
        s.compressor = true;
        s.limiter = true;
        for (int i = 0; i < BAND_COUNT; i++) s.bandDb[i] = 0f;
        s.bassDb = 0f;
        s.trebleDb = 0f;
        s.presenceDb = 0f;
        s.loudness = 1f;
        s.drive = 0f;
        s.comp2 = false;
        s.sustain = false;
        s.sustainTargetDb = -12f;
        s.sustainMaxGainDb = 6f;
        s.limiterDb = -0.5f;
        switch (preset) {
            case PRESET_OFF:
                s.enabled = false;
                s.inputGainDb = 0f;
                s.outputGainDb = 0f;
                s.noiseGate = false;
                s.compressor = false;
                s.amount = 0f;
                break;
            case PRESET_VOICE_BOOST:
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 6f;    // mic boost
                s.bandDb[0] = -4f;     // rumble cut
                s.bandDb[1] = -1f;
                s.bandDb[2] = +3f;     // body
                s.bandDb[3] = +5f;     // presence / intelligibility
                s.bandDb[4] = +2f;     // air
                s.bassDb = 2f;
                s.trebleDb = 3f;
                s.gateThresholdDb = -55f;
                s.compThresholdDb = -18f;
                s.compRatio = 3f;
                s.outputGainDb = 2f;
                break;
            case PRESET_STUDIO:
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 6f;
                s.bandDb[0] = -6f;
                s.bandDb[1] = -3f;
                s.bandDb[2] = +1f;
                s.bandDb[3] = +4f;
                s.bandDb[4] = +3f;
                s.bassDb = -1f;
                s.trebleDb = 4f;
                s.gateThresholdDb = -50f;
                s.compThresholdDb = -20f;
                s.compRatio = 4f;
                s.outputGainDb = 1f;
                break;
            case PRESET_BASS:
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 5f;
                s.bandDb[0] = +6f;
                s.bandDb[1] = +4f;
                s.bandDb[2] = +1f;
                s.bandDb[3] = +1f;
                s.bandDb[4] = -1f;
                s.bassDb = 7f;
                s.trebleDb = -1f;
                s.compThresholdDb = -16f;
                s.compRatio = 3.5f;
                s.outputGainDb = 2f;
                break;
            case PRESET_BRIGHT:
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 5f;
                s.bandDb[0] = -5f;
                s.bandDb[1] = -2f;
                s.bandDb[2] = +2f;
                s.bandDb[3] = +6f;
                s.bandDb[4] = +5f;
                s.bassDb = -2f;
                s.trebleDb = 6f;
                s.compThresholdDb = -18f;
                s.compRatio = 3f;
                s.outputGainDb = 2f;
                break;
            case PRESET_LOUD:
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 12f;
                s.bandDb[0] = -2f;
                s.bandDb[1] = +1f;
                s.bandDb[2] = +4f;
                s.bandDb[3] = +6f;
                s.bandDb[4] = +3f;
                s.bassDb = 3f;
                s.trebleDb = 3f;
                s.gateThresholdDb = -58f;
                s.compThresholdDb = -24f; // heavy compression = "loud" feel
                s.compRatio = 6f;
                s.outputGainDb = 6f;
                break;
            /* ---------------- reference kit ke modes ---------------- */
            case PRESET_CLEAR_BOOST:      // "Clear boost": +18 dB, halka presence
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 18f;
                s.presenceDb = 2f;
                s.trebleDb = 2f;
                s.bassDb = 0f;
                s.compThresholdDb = -28f;
                s.compRatio = 8f;
                s.loudness = 1.1f;
                s.limiterDb = -1f;
                s.gateThresholdDb = -58f;
                break;
            case PRESET_SAFE:             // "Safe": soft, distortion-free
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 12f;
                s.presenceDb = 1f;
                s.trebleDb = 1f;
                s.drive = 0.08f;
                s.compThresholdDb = -20f;
                s.compRatio = 4f;
                s.loudness = 1f;
                s.limiterDb = -1f;
                break;
            case PRESET_ROYAL:            // reference "royal"
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 24f;
                s.presenceDb = 4f;
                s.bassDb = 2f;
                s.trebleDb = 4f;
                s.drive = 0.18f;
                s.compThresholdDb = -30f;
                s.compRatio = 6f;
                s.loudness = 1.25f;
                s.sustain = true;
                s.sustainTargetDb = -18f;
                s.sustainMaxGainDb = 4f;
                s.limiterDb = -1.5f;
                s.gateThresholdDb = -60f;
                break;
            case PRESET_LORD:             // reference "lord"
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 30f;
                s.presenceDb = 5f;
                s.bassDb = 3f;
                s.trebleDb = 5f;
                s.drive = 0.35f;
                s.compThresholdDb = -24f;
                s.compRatio = 10f;
                s.loudness = 1.5f;
                s.sustain = true;
                s.sustainTargetDb = -15f;
                s.sustainMaxGainDb = 6f;
                s.limiterDb = -1f;
                s.gateThresholdDb = -60f;
                break;
            case PRESET_MAX_POWER:        // MAX POWER — sabse tez, "phone-phat" mode
                s.enabled = true;
                s.amount = 1f;
                s.inputGainDb = 36f;      // reference ka max
                s.bandDb[0] = 0f;
                s.bandDb[1] = 2f;
                s.bandDb[2] = 5f;
                s.bandDb[3] = 7f;
                s.bandDb[4] = 4f;
                s.bassDb = 4f;
                s.trebleDb = 5f;
                s.presenceDb = 6f;
                s.drive = 0.45f;
                s.compThresholdDb = -20f;
                s.compRatio = 12f;
                s.comp2 = true;           // aggressive second stage
                s.sustain = true;         // quiet bhi full volume
                s.sustainTargetDb = -6f;
                s.sustainMaxGainDb = 12f;
                s.loudness = 4f;          // 4x extra loudness
                s.outputGainDb = 12f;
                s.limiterDb = -0.3f;      // kaanch ke kinare tak
                s.gateThresholdDb = -65f; // gate almost khula (kuch na kate)
                break;
            case PRESET_CUSTOM:
            default:
                s.enabled = true;
                break;
        }
    }

    /* ------------------------------------------------------------------ *
     *  Biquad (RBJ audio EQ cookbook)
     * ------------------------------------------------------------------ */

    public static final class Biquad {
        float b0, b1, b2, a1, a2;
        float x1, x2, y1, y2;
        boolean bypass;

        void set(float b0, float b1, float b2, float a0, float a1, float a2) {
            final float inv = 1f / a0;
            this.b0 = b0 * inv;
            this.b1 = b1 * inv;
            this.b2 = b2 * inv;
            this.a1 = a1 * inv;
            this.a2 = a2 * inv;
            bypass = false;
        }

        void reset() {
            x1 = x2 = y1 = y2 = 0f;
        }

        float process(float x) {
            if (bypass) return x;
            final float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    private static double cos(double w) { return Math.cos(w); }
    private static double sin(double w) { return Math.sin(w); }

    /** Peaking EQ. gainDb = 0 -> bypass. */
    static void peaking(Biquad q, float sampleRate, float freq, float gainDb, float qFactor) {
        if (gainDb == 0f) { q.bypass = true; return; }
        final double A = Math.pow(10.0, gainDb / 40.0);
        final double w0 = 2.0 * Math.PI * clamp(freq, 20f, sampleRate * 0.45f) / sampleRate;
        final double alpha = sin(w0) / (2.0 * qFactor);
        final double a0 = 1 + alpha / A;
        q.set(
                (float) (1 + alpha * A), (float) (-2 * cos(w0)), (float) (1 - alpha * A),
                (float) a0, (float) (-2 * cos(w0)), (float) (1 - alpha / A));
    }

    /** Low shelf. */
    static void lowShelf(Biquad q, float sampleRate, float freq, float gainDb, float slope) {
        if (gainDb == 0f) { q.bypass = true; return; }
        final double A = Math.pow(10.0, gainDb / 40.0);
        final double w0 = 2.0 * Math.PI * clamp(freq, 20f, sampleRate * 0.45f) / sampleRate;
        final double cw = cos(w0), sw = sin(w0);
        final double alpha = sw / 2.0 * Math.sqrt((A + 1 / A) * (1 / slope - 1) + 2);
        final double sq = 2 * Math.sqrt(A) * alpha;
        final double a0 = (A + 1) + (A - 1) * cw + sq;
        q.set(
                (float) (A * ((A + 1) - (A - 1) * cw + sq)),
                (float) (2 * A * ((A - 1) - (A + 1) * cw)),
                (float) (A * ((A + 1) - (A - 1) * cw - sq)),
                (float) a0,
                (float) (-2 * ((A - 1) + (A + 1) * cw)),
                (float) ((A + 1) + (A - 1) * cw - sq));
    }

    /** High shelf. */
    static void highShelf(Biquad q, float sampleRate, float freq, float gainDb, float slope) {
        if (gainDb == 0f) { q.bypass = true; return; }
        final double A = Math.pow(10.0, gainDb / 40.0);
        final double w0 = 2.0 * Math.PI * clamp(freq, 20f, sampleRate * 0.45f) / sampleRate;
        final double cw = cos(w0), sw = sin(w0);
        final double alpha = sw / 2.0 * Math.sqrt((A + 1 / A) * (1 / slope - 1) + 2);
        final double sq = 2 * Math.sqrt(A) * alpha;
        final double a0 = (A + 1) - (A - 1) * cw + sq;
        q.set(
                (float) (A * ((A + 1) + (A - 1) * cw + sq)),
                (float) (-2 * A * ((A - 1) + (A + 1) * cw)),
                (float) (A * ((A + 1) + (A - 1) * cw - sq)),
                (float) a0,
                (float) (2 * ((A - 1) - (A + 1) * cw)),
                (float) ((A + 1) - (A - 1) * cw - sq));
    }

    /** 2nd order high-pass (RBJ), Butterworth-ish. */
    static void highPass(Biquad q, float sampleRate, float freq, float qFactor) {
        final double w0 = 2.0 * Math.PI * clamp(freq, 20f, sampleRate * 0.45f) / sampleRate;
        final double cw = cos(w0), sw = sin(w0);
        final double alpha = sw / (2.0 * qFactor);
        final double a0 = 1 + alpha;
        q.set(
                (float) ((1 + cw) / 2), (float) (-(1 + cw)), (float) ((1 + cw) / 2),
                (float) a0, (float) (-2 * cw), (float) (1 - alpha));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /* ------------------------------------------------------------------ *
     *  The processor
     * ------------------------------------------------------------------ */

    /**
     * One instance per (sampleRate, channels). NOT thread safe — create a new one
     * whenever the format changes, and only touch it from the audio thread.
     */
    public static final class Processor {
        private final Settings settings = new Settings();
        private int sampleRate = 48000;
        private int channels = 1;

        private final Biquad[] bands = new Biquad[BAND_COUNT];
        private final Biquad bass = new Biquad();
        private final Biquad presence = new Biquad();   // reference ka 3.2 kHz peak
        private final Biquad treble = new Biquad();
        private final Biquad hp = new Biquad();

        // gate / compressor envelopes (per channel)
        private float[] gateEnv;
        private float[] compEnv;
        private float[] gateGain;
        private float[] comp2Env;
        private float[] susEnv;
        private float[] susGain;
        private int gateHold;

        private float inGain = 1f, outGain = 1f;
        private float gateLin = 0.003f;
        private float compThresh = 0.125f;
        private float compRatio = 3f;
        /** Static makeup gain (compression ke baad loudness wapas laata hai). */
        private float compMakeup = 1f;
        private float wet = 1f;
        private boolean active = false;

        // v2 chain (reference kit se): drive / loudness / sustain / limiter ceiling
        private boolean useComp2 = false;
        private boolean useSustain = false;
        private float driveK = 0f;            // 0 = off
        private float loudnessGain = 1f;      // 1 = off, up to 8x
        private float susTarget = 0.25f;      // linear target level (from sustainTargetDb)
        private float susMaxGain = 2f;
        private float ceiling = 0.944f;       // = dbToLin(limiterDb); -0.5 dBFS default

        public Processor() {
            for (int i = 0; i < BAND_COUNT; i++) bands[i] = new Biquad();
            allocate(1);
        }

        private void allocate(int ch) {
            gateEnv = new float[ch];
            compEnv = new float[ch];
            gateGain = new float[ch];
            comp2Env = new float[ch];
            susEnv = new float[ch];
            susGain = new float[ch];
            for (int c = 0; c < ch; c++) {
                gateGain[c] = 1f;
                susGain[c] = 1f;
            }
        }

        public int getSampleRate() { return sampleRate; }
        public int getChannels() { return channels; }
        public boolean isActive() { return active; }

        /**
         * Rebuild filters. Cheap enough to call on setting change, NOT per frame.
         * @param s      settings (copied)
         * @param rate   sample rate of the stream (44100 / 48000)
         * @param ch     1 (mono) or 2
         */
        public void configure(Settings s, int rate, int ch) {
            this.sampleRate = rate > 0 ? rate : 48000;
            this.channels = ch > 0 ? ch : 1;
            if (gateEnv == null || gateEnv.length != this.channels) allocate(this.channels);

            settings.enabled = s.enabled;
            settings.preset = s.preset;
            settings.amount = clamp(s.amount, 0f, 1f);
            settings.inputGainDb = clamp(s.inputGainDb, -12f, 36f);   // reference max = 36 dB
            settings.outputGainDb = clamp(s.outputGainDb, -24f, 18f);
            for (int i = 0; i < BAND_COUNT; i++) settings.bandDb[i] = clamp(s.bandDb[i], -18f, 18f);
            settings.bassDb = clamp(s.bassDb, -12f, 12f);
            settings.trebleDb = clamp(s.trebleDb, -12f, 12f);
            settings.highPass = s.highPass;
            settings.noiseGate = s.noiseGate;
            settings.gateThresholdDb = s.gateThresholdDb;
            settings.compressor = s.compressor;
            settings.compThresholdDb = s.compThresholdDb;
            settings.compRatio = s.compRatio;
            settings.limiter = s.limiter;
            settings.loudness = clamp(s.loudness, 0.5f, 8f);
            settings.drive = clamp(s.drive, 0f, 1f);
            settings.presenceDb = clamp(s.presenceDb, -18f, 18f);
            settings.comp2 = s.comp2;
            settings.sustain = s.sustain;
            settings.sustainTargetDb = clamp(s.sustainTargetDb, -40f, -3f);
            settings.sustainMaxGainDb = clamp(s.sustainMaxGainDb, 0f, 18f);
            settings.limiterDb = clamp(s.limiterDb, -6f, 0f);

            final int nBands = this.sampleRate < 16000 ? 3 : BAND_COUNT;
            for (int i = 0; i < BAND_COUNT; i++) {
                if (i < nBands) peaking(bands[i], this.sampleRate, BAND_FREQ[i], settings.bandDb[i], 0.9f);
                else bands[i].bypass = true;
                bands[i].reset();
            }
            lowShelf(bass, this.sampleRate, 200f, settings.bassDb, 0.9f);
            // reference kit ka presence peak (3.2 kHz, Q 1.5)
            peaking(presence, this.sampleRate, 3200f, settings.presenceDb, 1.5f);
            presence.reset();
            highShelf(treble, this.sampleRate, 6000f, settings.trebleDb, 0.9f);
            if (settings.highPass) highPass(hp, this.sampleRate, 90f, 0.707f);
            else hp.bypass = true;
            hp.reset();
            bass.reset();
            treble.reset();

            inGain = dbToLin(settings.inputGainDb);
            outGain = dbToLin(settings.outputGainDb);
            gateLin = dbToLin(settings.gateThresholdDb);
            compThresh = dbToLin(settings.compThresholdDb);
            compRatio = settings.compRatio;
            // Standard "auto makeup": (1 - 1/R) * |T| dB, aadha (0.5) hi lagate hain
            // taaki awaaz squash na lage. Clamp 0.5x .. 4x.
            {
                final float makeupDb = (1f - 1f / compRatio) * (-settings.compThresholdDb) * 0.5f;
                compMakeup = clamp(dbToLin(makeupDb), 0.5f, 4f);
            }
            wet = settings.amount;

            // v2 stages
            useComp2 = settings.comp2;
            useSustain = settings.sustain;
            driveK = settings.drive;
            loudnessGain = settings.loudness;
            susTarget = dbToLin(settings.sustainTargetDb);
            susMaxGain = dbToLin(settings.sustainMaxGainDb);
            ceiling = dbToLin(settings.limiterDb);   // limiter ki deewar

            for (int c = 0; c < this.channels; c++) {
                gateEnv[c] = 0f;
                compEnv[c] = 0f;
                gateGain[c] = 1f;
                comp2Env[c] = 0f;
                susEnv[c] = 0f;
                susGain[c] = 1f;
            }
            active = settings.enabled && (wet > 0.0001f);
        }

        public void reset() {
            for (Biquad b : bands) b.reset();
            bass.reset();
            presence.reset();
            treble.reset();
            hp.reset();
            if (gateEnv != null) for (int c = 0; c < gateEnv.length; c++) {
                gateEnv[c] = 0f; compEnv[c] = 0f; gateGain[c] = 1f;
                comp2Env[c] = 0f; susEnv[c] = 0f; susGain[c] = 1f;
            }
        }

        // envelope time constants
        private float envCoef(float ms) {
            return (float) Math.exp(-1.0 / (Math.max(0.1f, ms) * 0.001 * sampleRate));
        }

        /**
         * Process interleaved PCM16 in place.
         *
         * @param pcm    samples
         * @param off    index of the first sample (in shorts) — arrayOffset
         * @param count  number of shorts (frames * channels)
         */
        public void process(short[] pcm, int off, int count) {
            if (!active || count <= 0) return;
            final int ch = channels;
            final int frames = count / ch;
            // Gate detection = RMS-ish (30 ms) taaki short peaks se gate poora na khule.
            // Gate gain khud 5 ms me khulta hai (speech ka pehla syllable na kate).
            final float gateEnvAtk = envCoef(30f), gateEnvRel = envCoef(150f);
            final float gateAtk = envCoef(5f), gateRel = envCoef(150f);
            final float compAtk = envCoef(8f), compRel = envCoef(140f);
            final boolean useGate = settings.noiseGate;
            final boolean useComp = settings.compressor;
            final boolean useLim = settings.limiter;
            final boolean useC2 = useComp2;
            final boolean useSus = useSustain;
            final float drK = driveK;
            final float loud = loudnessGain;
            final float susT = susTarget, susMax = susMaxGain;
            final float susAtk = envCoef(150f), susRel = envCoef(700f), susSm = envCoef(250f);
            // doosra compressor (reference comp2): threshold -10 dB, 12:1, fast
            final float c2Thresh = 0.316f, c2Ratio = 12f;
            final float c2Atk = envCoef(1f), c2Rel = envCoef(50f);
            final float dryMix = 1f - wet;
            final float inv = 1f / 32768f;
            final float makeup = compMakeup;

            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < ch; c++) {
                    final int idx = off + f * ch + c;
                    // poori chain normalized (-1..1) domain me chalti hai,
                    // isi liye gate/compressor/limiter ke thresholds (dB -> linear 0..1) sahi lagte hain
                    final float dry = pcm[idx] * inv;
                    float x = dry * inGain;

                    x = hp.process(x);
                    for (int b = 0; b < BAND_COUNT; b++) x = bands[b].process(x);
                    x = bass.process(x);
                    x = presence.process(x);          // 3.2 kHz presence
                    x = treble.process(x);

                    if (useGate) {
                        final float a = Math.abs(x);
                        final float coef = a > gateEnv[c] ? gateEnvAtk : gateEnvRel;
                        gateEnv[c] = a + coef * (gateEnv[c] - a);
                        // Soft gate / downward expander: threshold ke upar khula,
                        // neeche jaate hi square-law se band. Floor = -34 dB
                        // (itna deep sirf tab jab aap threshold se bahut neeche ho).
                        final float open = gateEnv[c] / Math.max(1e-6f, gateLin);
                        float target = open < 1f ? clamp(open * open, 0.02f, 1f) : 1f;
                        final float gcoef = target > gateGain[c] ? gateAtk : gateRel;
                        gateGain[c] = target + gcoef * (gateGain[c] - target);
                        x *= gateGain[c];
                    }

                    if (useComp) {
                        final float a = Math.abs(x);
                        final float coef = a > compEnv[c] ? compAtk : compRel;
                        compEnv[c] = a + coef * (compEnv[c] - a);
                        if (compEnv[c] > compThresh) {
                            // gain computer: gain = (env/threshold)^(1/R - 1)
                            final float over = compEnv[c] / compThresh;
                            x *= (float) Math.pow(over, 1.0 / compRatio - 1.0);
                        }
                        x *= makeup;
                    }

                    // ---- doosra (aggressive) compressor — reference comp2 ----
                    if (useC2) {
                        final float a2 = Math.abs(x);
                        final float coef2 = a2 > comp2Env[c] ? c2Atk : c2Rel;
                        comp2Env[c] = a2 + coef2 * (comp2Env[c] - a2);
                        if (comp2Env[c] > c2Thresh) {
                            final float over = comp2Env[c] / c2Thresh;
                            x *= (float) Math.pow(over, 1.0 / c2Ratio - 1.0);
                        }
                    }

                    // ---- saturator / drive (reference ka waveshaper) ----
                    if (drK > 0.0001f) {
                        final float push = x * (1f + drK * 3f);
                        x = push / (1f + Math.abs(push));   // soft clip, bounded
                    }

                    // ---- extra loudness (maxBoost) ----
                    if (loud != 1f) x *= loud;

                    // ---- sustain (AGC): halki awaaz ko target tak uthao ----
                    if (useSus) {
                        final float as = Math.abs(x);
                        final float coefS = as > susEnv[c] ? susAtk : susRel;
                        susEnv[c] = as + coefS * (susEnv[c] - as);
                        if (susEnv[c] > 1e-5f) {
                            final float needDb = linToDb(susT) - linToDb(susEnv[c]);
                            final float g = clamp(dbToLin(needDb), 1f, susMax);
                            susGain[c] = g + susSm * (susGain[c] - g);
                        }
                        x *= susGain[c];
                    }

                    x *= outGain;

                    if (useLim) {
                        // soft-knee brick wall: ceiling ke upar 4:1 dab jata hai
                        // (ceiling = limiterDb, default -0.5 dBFS)
                        if (x > ceiling) x = ceiling + (x - ceiling) * 0.25f;
                        else if (x < -ceiling) x = -ceiling + (x + ceiling) * 0.25f;
                    }

                    float y = dry * dryMix + x * wet;
                    if (y > 1f) y = 1f;
                    else if (y < -1f) y = -1f;
                    pcm[idx] = (short) Math.round(y * 32767f);
                }
            }
        }
    }

    public static float dbToLin(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    public static float linToDb(float lin) {
        if (lin <= 1e-6f) return -120f;
        return (float) (20.0 * Math.log10(lin));
    }

    private VoiceDsp() {}
}
