package org.telegram.messenger.gramify;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.ByteBuffer;

/**
 * Gramify VoiceEnhancer — call audio path aur Gramify DSP ke beech ka glue.
 *
 * <h3>Hook kahan lagta hai (v2 — 3 paths, kyunki Telegram 3 tareeke se mic padhta hai)</h3>
 * <ol>
 *   <li><b>PRIMARY (calls + video calls + group calls)</b>:
 *       {@code org.webrtc.voiceengine.WebRtcAudioRecord.AudioRecordThread#run()}
 *       — native {@code modules/audio_device/android/audio_record_jni.cc}
 *       {@code RegisterNatives("org/webrtc/voiceengine/WebRtcAudioRecord")} karta hai,
 *       yani asli mic reading yahi Java loop hai.</li>
 *   <li>{@code org.webrtc.audio.WebRtcAudioRecord} (Java ADM / PeerConnection path)</li>
 *   <li>{@code org.telegram.messenger.voip.AudioRecordJNI} (purana tgVoip path)</li>
 * </ol>
 *
 * <h3>Zaroori: buffer DIRECT hota hai</h3>
 * Upar ki saari classes me mic buffer {@code ByteBuffer.allocateDirect(...)} se banta hai.
 * Direct buffer me {@code hasArray()} {@code false} deta hai — isliye yahan
 * {@code duplicate()}+absolute copy se data padha/likha jaata hai (v1 me yahi bug tha
 * jisse enhancer chup-chaap kuch nahi karta tha).
 *
 * Real-time audio thread se call hota hai, isliye:
 *  - koi exception throw nahi (warna call drop)
 *  - koi disk/network nahi — sab in-memory
 *  - per-frame allocation zero (scratch buffers reuse hote hain)
 */
public final class VoiceEnhancer {

    private static final String PREFS = "gramify_voice";

    public static final String KEY_ENABLED   = "enabled";
    public static final String KEY_PRESET    = "preset";
    public static final String KEY_AMOUNT    = "amount";
    public static final String KEY_IN_GAIN   = "in_gain";
    public static final String KEY_OUT_GAIN  = "out_gain";
    public static final String KEY_BASS      = "bass";
    public static final String KEY_TREBLE    = "treble";
    public static final String KEY_GATE      = "gate";
    public static final String KEY_COMP      = "comp";
    public static final String KEY_BAND      = "band_";
    public static final String KEY_SHARE     = "music_share";
    public static final String KEY_SHARE_VOL = "music_volume";
    public static final String KEY_DUCK      = "music_duck";
    public static final String KEY_LOUD      = "loudness";
    public static final String KEY_DRIVE     = "drive";
    public static final String KEY_PRESENCE  = "presence";
    public static final String KEY_COMP2     = "comp2";
    public static final String KEY_SUSTAIN   = "sustain";
    public static final String KEY_SUS_TGT   = "sustain_target";
    public static final String KEY_SUS_MAX   = "sustain_max";
    public static final String KEY_LIMITER   = "limiter_db";

    private static volatile boolean loaded = false;
    private static volatile boolean enabled = false;
    private static volatile int preset = VoiceDsp.PRESET_VOICE_BOOST;
    private static volatile float amount = 1f;
    private static volatile float inGain = 9f;
    private static volatile float outGain = 3f;
    private static volatile float bass = 0f;
    private static volatile float treble = 0f;
    private static volatile boolean gate = true;
    private static volatile boolean comp = true;
    private static volatile float loudness = 1f;
    private static volatile float drive = 0f;
    private static volatile float presenceDb = 0f;
    private static volatile boolean comp2 = false;
    private static volatile boolean sustain = false;
    private static volatile float sustainTargetDb = -12f;
    private static volatile float sustainMaxGainDb = 6f;
    private static volatile float limiterDb = -0.5f;
    private static volatile boolean micMuted = false;
    private static final float[] bandDb = new float[VoiceDsp.BAND_COUNT];

    private static volatile int generation = 0;   // settings revision
    private static int builtGen = -1;
    private static int builtRate = -1;
    private static int builtCh = -1;

    private static VoiceDsp.Processor processor;

    /** diagnostics (settings UI me dikhate hain — proof ki hook zinda hai) */
    private static volatile float lastInRms = 0f;
    private static volatile float lastOutRms = 0f;
    private static volatile long framesProcessed = 0;
    private static volatile int lastSampleRate = 0;
    private static volatile int lastChannels = 0;
    private static volatile long lastHookTime = 0L;

    private static SharedPreferences prefs;

    private VoiceEnhancer() {}

    /* ------------------------------------------------------------------ *
     *  load / save
     * ------------------------------------------------------------------ */

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = prefs;
        if (p == null) {
            p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs = p;
        }
        return p;
    }

    public static void load(Context context) {
        if (context == null) return;
        final SharedPreferences p = prefs(context);
        enabled = p.getBoolean(KEY_ENABLED, false);
        preset = p.getInt(KEY_PRESET, VoiceDsp.PRESET_VOICE_BOOST);
        amount = p.getFloat(KEY_AMOUNT, 1f);
        inGain = p.getFloat(KEY_IN_GAIN, 9f);
        outGain = p.getFloat(KEY_OUT_GAIN, 3f);
        bass = p.getFloat(KEY_BASS, 0f);
        treble = p.getFloat(KEY_TREBLE, 0f);
        gate = p.getBoolean(KEY_GATE, true);
        comp = p.getBoolean(KEY_COMP, true);
        loudness = p.getFloat(KEY_LOUD, 1f);
        drive = p.getFloat(KEY_DRIVE, 0f);
        presenceDb = p.getFloat(KEY_PRESENCE, 0f);
        comp2 = p.getBoolean(KEY_COMP2, false);
        sustain = p.getBoolean(KEY_SUSTAIN, false);
        sustainTargetDb = p.getFloat(KEY_SUS_TGT, -12f);
        sustainMaxGainDb = p.getFloat(KEY_SUS_MAX, 6f);
        limiterDb = p.getFloat(KEY_LIMITER, -0.5f);
        for (int i = 0; i < VoiceDsp.BAND_COUNT; i++) {
            bandDb[i] = p.getFloat(KEY_BAND + i, 0f);
        }
        CallMixer.setVolume(p.getFloat(KEY_SHARE_VOL, 0.75f));
        CallMixer.setDuck(p.getFloat(KEY_DUCK, 0.5f));
        loaded = true;
        generation++;
    }

    public static boolean isLoaded() { return loaded; }

    private static void save(Context c) {
        if (c == null) return;
        prefs(c).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_PRESET, preset)
                .putFloat(KEY_AMOUNT, amount)
                .putFloat(KEY_IN_GAIN, inGain)
                .putFloat(KEY_OUT_GAIN, outGain)
                .putFloat(KEY_BASS, bass)
                .putFloat(KEY_TREBLE, treble)
                .putBoolean(KEY_GATE, gate)
                .putBoolean(KEY_COMP, comp)
                .putFloat(KEY_LOUD, loudness)
                .putFloat(KEY_DRIVE, drive)
                .putFloat(KEY_PRESENCE, presenceDb)
                .putBoolean(KEY_COMP2, comp2)
                .putBoolean(KEY_SUSTAIN, sustain)
                .putFloat(KEY_SUS_TGT, sustainTargetDb)
                .putFloat(KEY_SUS_MAX, sustainMaxGainDb)
                .putFloat(KEY_LIMITER, limiterDb)
                .putFloat(KEY_SHARE_VOL, CallMixer.getVolume())
                .putFloat(KEY_DUCK, CallMixer.getDuck())
                .apply();
        generation++;
    }

    /* ------------------------------------------------------------------ *
     *  getters / setters (UI)
     * ------------------------------------------------------------------ */

    public static boolean isEnabled() { return enabled; }
    public static int getPreset() { return preset; }
    public static float getAmount() { return amount; }
    public static float getInputGainDb() { return inGain; }
    public static float getOutputGainDb() { return outGain; }
    public static float getBassDb() { return bass; }
    public static float getTrebleDb() { return treble; }
    public static boolean isGateEnabled() { return gate; }
    public static boolean isCompEnabled() { return comp; }
    public static float getBandDb(int i) { return (i >= 0 && i < VoiceDsp.BAND_COUNT) ? bandDb[i] : 0f; }
    public static float getLoudness() { return loudness; }
    public static float getDrive() { return drive; }
    public static float getPresenceDb() { return presenceDb; }
    public static boolean isComp2Enabled() { return comp2; }
    public static boolean isSustainEnabled() { return sustain; }
    public static float getSustainTargetDb() { return sustainTargetDb; }
    public static float getSustainMaxGainDb() { return sustainMaxGainDb; }
    public static float getLimiterDb() { return limiterDb; }
    /** Telegram mic mute — mute hone pe music bhi band (feature: "mute = song band"). */
    public static boolean isMicMuted() { return micMuted; }

    /** Music layer ko mute ka pata dene ke liye (loop se decoupled, pure-Java rehta hai). */
    public interface MuteListener { void onMicMuteChanged(boolean muted); }

    private static volatile MuteListener muteListener;

    public static void setMuteListener(MuteListener l) { muteListener = l; }

    public static float getLastInRms() { return lastInRms; }
    public static float getLastOutRms() { return lastOutRms; }
    public static long getFramesProcessed() { return framesProcessed; }
    public static int getLastSampleRate() { return lastSampleRate; }
    public static int getLastChannels() { return lastChannels; }
    /** hook zinda hai? (last 1 second me audio aaya) */
    public static boolean isHookAlive() {
        return lastHookTime != 0 && (System.currentTimeMillis() - lastHookTime) < 1000;
    }

    public static void setEnabled(Context c, boolean v) {
        enabled = v;
        if (v && preset == VoiceDsp.PRESET_OFF) preset = VoiceDsp.PRESET_VOICE_BOOST;
        generation++;
        save(c);
    }

    public static void setPreset(Context c, int p) {
        preset = p;
        final VoiceDsp.Settings s = snapshot();
        VoiceDsp.applyPreset(s, p);
        enabled = s.enabled;
        inGain = s.inputGainDb;
        outGain = s.outputGainDb;
        bass = s.bassDb;
        treble = s.trebleDb;
        gate = s.noiseGate;
        comp = s.compressor;
        amount = s.amount;
        for (int i = 0; i < VoiceDsp.BAND_COUNT; i++) bandDb[i] = s.bandDb[i];
        loudness = s.loudness;
        drive = s.drive;
        presenceDb = s.presenceDb;
        comp2 = s.comp2;
        sustain = s.sustain;
        sustainTargetDb = s.sustainTargetDb;
        sustainMaxGainDb = s.sustainMaxGainDb;
        limiterDb = s.limiterDb;
        generation++;
        save(c);
    }

    public static void setAmount(Context c, float v) { amount = clamp(v, 0f, 1f); generation++; save(c); }
    public static void setInputGainDb(Context c, float v) { inGain = clamp(v, -12f, 36f); generation++; save(c); }
    public static void setOutputGainDb(Context c, float v) { outGain = clamp(v, -24f, 18f); generation++; save(c); }
    public static void setBassDb(Context c, float v) { bass = clamp(v, -12f, 12f); generation++; save(c); }
    public static void setTrebleDb(Context c, float v) { treble = clamp(v, -12f, 12f); generation++; save(c); }
    public static void setGate(Context c, boolean v) { gate = v; generation++; save(c); }
    public static void setComp(Context c, boolean v) { comp = v; generation++; save(c); }

    public static void setLoudness(Context c, float v) { loudness = clamp(v, 0.5f, 8f); generation++; save(c); }
    public static void setDrive(Context c, float v) { drive = clamp(v, 0f, 1f); generation++; save(c); }
    public static void setPresenceDb(Context c, float v) { presenceDb = clamp(v, -18f, 18f); generation++; save(c); }
    public static void setComp2(Context c, boolean v) { comp2 = v; generation++; save(c); }
    public static void setSustain(Context c, boolean v) { sustain = v; generation++; save(c); }
    public static void setSustainTargetDb(Context c, float v) { sustainTargetDb = clamp(v, -40f, -3f); generation++; save(c); }
    public static void setSustainMaxGainDb(Context c, float v) { sustainMaxGainDb = clamp(v, 0f, 18f); generation++; save(c); }
    public static void setLimiterDb(Context c, float v) { limiterDb = clamp(v, -6f, 0f); generation++; save(c); }

    public static void setBandDb(Context c, int i, float v) {
        if (i < 0 || i >= VoiceDsp.BAND_COUNT) return;
        bandDb[i] = clamp(v, -18f, 18f);
        preset = VoiceDsp.PRESET_CUSTOM;
        generation++;
        save(c);
    }

    /* ---- music ko call me bhejne ka volume/duck (MusicShare UI se control hota hai) ---- */

    public static float getMusicVolume() { return CallMixer.getVolume(); }

    public static void setMusicVolume(Context c, float v) {
        CallMixer.setVolume(clamp(v, 0f, 1.5f));
        save(c);
    }

    public static float getMicDuck() { return CallMixer.getDuck(); }

    public static void setMicDuck(Context c, float v) {
        CallMixer.setDuck(clamp(v, 0f, 1f));
        save(c);
    }

    private static void prefsBoolean(Context c, String key, boolean v) {
        if (c == null) return;
        prefs(c).edit().putBoolean(key, v).apply();
    }

    public static void reset(Context c) {
        preset = VoiceDsp.PRESET_VOICE_BOOST;
        final VoiceDsp.Settings s = snapshot();
        VoiceDsp.applyPreset(s, preset);
        enabled = s.enabled; inGain = s.inputGainDb; outGain = s.outputGainDb;
        bass = s.bassDb; treble = s.trebleDb; gate = s.noiseGate; comp = s.compressor; amount = s.amount;
        for (int i = 0; i < VoiceDsp.BAND_COUNT; i++) bandDb[i] = s.bandDb[i];
        loudness = s.loudness;
        drive = s.drive;
        presenceDb = s.presenceDb;
        comp2 = s.comp2;
        sustain = s.sustain;
        sustainTargetDb = s.sustainTargetDb;
        sustainMaxGainDb = s.sustainMaxGainDb;
        limiterDb = s.limiterDb;
        generation++;
        save(c);
    }

    public static VoiceDsp.Settings snapshot() {
        final VoiceDsp.Settings s = new VoiceDsp.Settings();
        s.enabled = enabled;
        s.preset = preset;
        s.amount = amount;
        s.inputGainDb = inGain;
        s.outputGainDb = outGain;
        s.bassDb = bass;
        s.trebleDb = treble;
        s.noiseGate = gate;
        s.compressor = comp;
        for (int i = 0; i < VoiceDsp.BAND_COUNT; i++) s.bandDb[i] = bandDb[i];
        s.loudness = loudness;
        s.drive = drive;
        s.presenceDb = presenceDb;
        s.comp2 = comp2;
        s.sustain = sustain;
        s.sustainTargetDb = sustainTargetDb;
        s.sustainMaxGainDb = sustainMaxGainDb;
        s.limiterDb = limiterDb;
        return s;
    }

    /* ------------------------------------------------------------------ *
     *  THE HOOKS
     * ------------------------------------------------------------------ */

    /** do hi cheez active hain? warna turant return (zero cost). */
    private static boolean nothingToDo() {
        return !enabled && !CallMixer.isActive();
    }

    /**
     * ByteBuffer version — Telegram DIRECT buffers use karta hai.
     * Heap ho ya direct, dono me in-place process hota hai.
     */
    public static void onOutgoingPcm(ByteBuffer buffer, int byteCount, int sampleRate, int channels) {
        onOutgoingPcm(buffer, byteCount, sampleRate, channels, false);
    }

    /**
     * Naya hook (Telegram ke mic-mute flag ke saath).
     *
     * <p><b>Mute ka matlab:</b> aap mute ho → aapki awaaz nahi jaati, to gaana bhi nahi
     * jayega ("song band ho jayega"). Isliye muted=true pe hum kuch bhi mix nahi karte,
     * aur music engine ko pause kar dete hain.</p>
     */
    public static void onOutgoingPcm(ByteBuffer buffer, int byteCount, int sampleRate, int channels,
                                     boolean muted) {
        handleMute(muted);
        if (muted) return;                       // mute = silence + music band
        if (buffer == null || byteCount <= 0 || nothingToDo()) return;
        try {
            if (sampleRate <= 0) sampleRate = 48000;
            if (channels <= 0) channels = 1;
            markHook(sampleRate, channels);

            final int capacity = buffer.capacity();
            final int n = Math.min(byteCount, capacity);
            if (n < 4) return;

            if (buffer.hasArray()) {
                // heap buffer (WebRtcAudioRecord ke audioSamplesReadyCallback wale cases)
                processBytes(buffer.array(), buffer.arrayOffset(), n, sampleRate, channels);
            } else {
                // >>> DIRECT BUFFER — yahi calls me hota hai <<<
                // duplicate() se wahi memory share hoti hai, par position/limit alag rehte hain,
                // isliye original buffer ka state safe rehta hai.
                final byte[] scratch = bytesFor(n);
                final ByteBuffer dup = buffer.duplicate();
                dup.position(0);
                dup.limit(n);
                dup.get(scratch, 0, n);

                processBytes(scratch, 0, n, sampleRate, channels);

                dup.position(0);
                dup.limit(n);
                dup.put(scratch, 0, n);
            }
        } catch (Throwable ignore) {
            // call quality > feature: kuch bhi ho, audio chalta rahe
        }
    }

    /** byte[] version (legacy {@code AudioRecordJNI} path + tests) */
    public static void onOutgoingPcm(byte[] data, int offset, int byteCount, int sampleRate, int channels) {
        onOutgoingPcm(data, offset, byteCount, sampleRate, channels, false);
    }

    public static void onOutgoingPcm(byte[] data, int offset, int byteCount, int sampleRate, int channels,
                                     boolean muted) {
        handleMute(muted);
        if (muted) return;
        if (data == null || byteCount < 4 || nothingToDo()) return;
        try {
            if (sampleRate <= 0) sampleRate = 48000;
            if (channels <= 0) channels = 1;
            markHook(sampleRate, channels);
            processBytes(data, offset, Math.min(byteCount, data.length - offset), sampleRate, channels);
        } catch (Throwable ignore) {
        }
    }

    /** mute flag badla? to music engine ko batao (call/music band ho jaye). */
    private static void handleMute(boolean muted) {
        if (muted == micMuted) return;
        micMuted = muted;
        final MuteListener l = muteListener;
        if (l != null) {
            try {
                l.onMicMuteChanged(muted);
            } catch (Throwable ignore) {
            }
        }
    }

    private static void markHook(int sampleRate, int channels) {
        lastSampleRate = sampleRate;
        lastChannels = channels;
        lastHookTime = System.currentTimeMillis();
    }

    /**
     * Asli processing: bytes -> shorts -> [DSP] -> [music mix] -> shorts -> bytes
     */
    private static void processBytes(byte[] data, int offset, int byteCount, int sampleRate, int channels) {
        final int count = byteCount / 2;      // 16-bit PCM
        if (count <= 0) return;

        final short[] pcm = shortsFor(count);
        long inSum = 0;
        int p = offset;
        for (int i = 0; i < count; i++, p += 2) {
            final short v = (short) (((data[p + 1] & 0xFF) << 8) | (data[p] & 0xFF));
            pcm[i] = v;
            inSum += (long) v * v;
        }

        boolean didSomething = false;

        // 1) voice DSP (agar enhancer ON hai)
        if (enabled) {
            if (processor == null) processor = new VoiceDsp.Processor();
            if (builtGen != generation || builtRate != sampleRate || builtCh != channels
                    || !processor.isActive()) {
                processor.configure(snapshot(), sampleRate, channels);
                builtGen = generation;
                builtRate = sampleRate;
                builtCh = channels;
            }
            if (processor.isActive()) {
                processor.process(pcm, 0, count);
                didSomething = true;
            }
        }

        // 2) music injection (agar "call me share" ON hai)
        if (CallMixer.isActive() && CallMixer.availableFrames() > 0) {
            if (CallMixer.mixInto(pcm, 0, count, sampleRate, channels)) {
                didSomething = true;
            }
        }

        if (!didSomething) return;

        long outSum = 0;
        p = offset;
        for (int i = 0; i < count; i++, p += 2) {
            final short v = pcm[i];
            data[p] = (byte) (v & 0xFF);
            data[p + 1] = (byte) ((v >> 8) & 0xFF);
            outSum += (long) v * v;
        }

        framesProcessed += count / Math.max(1, channels);
        if ((framesProcessed & 1023) < (count / Math.max(1, channels))) {
            lastInRms = (float) Math.sqrt(inSum / (double) count) / 32768f;
            lastOutRms = (float) Math.sqrt(outSum / (double) count) / 32768f;
        }
    }

    /* reusable scratch (audio thread only) */
    private static short[] shorts;
    private static byte[] bytes;

    private static short[] shortsFor(int n) {
        short[] s = shorts;
        if (s == null || s.length < n) {
            s = new short[n + 2048];
            shorts = s;
        }
        return s;
    }

    private static byte[] bytesFor(int n) {
        byte[] b = bytes;
        if (b == null || b.length < n) {
            b = new byte[n + 4096];
            bytes = b;
        }
        return b;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
