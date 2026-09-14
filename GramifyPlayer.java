package org.telegram.messenger.gramify;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;

/**
 * Music player — MediaPlayer + system Equalizer/BassBoost.
 *
 * Yahan "second equaliser" ka wo hissa hai jo **music playback** par lagta hai
 * (voice enhancer alag hai — wo calls ki outgoing voice par lagta hai,
 * dekho {@link VoiceEnhancer}).
 *
 * Ek hi song ek time par chalega (simple rakha hai). Telegram ke andar ye
 * fragment band hone par pause ho jata hai.
 */
public final class GramifyPlayer {

    public interface Listener {
        void onState(boolean playing, Song song, String error);
    }

    public static final int EQ_FLAT = 0;
    public static final int EQ_BASS = 1;
    public static final int EQ_VOCAL = 2;
    public static final int EQ_LOUDNESS = 3;
    public static final String[] EQ_NAME = {"Flat", "Bass Boost", "Vocal", "Loudness"};

    private static GramifyPlayer instance;

    private MediaPlayer player;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Song current;
    private String streamUrl;
    private Context appContext;
    private Listener listener;
    private int eqPreset = EQ_FLAT;
    private boolean prepared;

    public static synchronized GramifyPlayer get() {
        if (instance == null) instance = new GramifyPlayer();
        return instance;
    }

    private GramifyPlayer() {}

    public void setListener(Listener l) { this.listener = l; }

    public Song getCurrent() { return current; }

    /** abhi jo URL chal raha hai — music-share (call me gaana) ke liye zaroori */
    public String getStreamUrl() { return streamUrl; }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    public int getEqPreset() { return eqPreset; }

    public void play(Context context, Song song, String streamUrl) {
        if (song == null || streamUrl == null) return;
        try {
            stopInternal();
            appContext = context.getApplicationContext();
            current = song;
            this.streamUrl = streamUrl;
            player = new MediaPlayer();
            player.setAudioAttributes(buildAttrs());
            player.setDataSource(streamUrl);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                attachEffects();
                mp.start();
                notifyState(null);
            });
            player.setOnCompletionListener(mp -> notifyState(null));
            player.setOnErrorListener((mp, what, extra) -> {
                notifyState("player error " + what + "/" + extra);
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            notifyState(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public void toggle() {
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
            else player.start();
            notifyState(null);
        } catch (Throwable ignore) {
        }
    }

    public void stop() {
        stopInternal();
        notifyState(null);
    }


    /**
     * Android 10+: is playback ko call me capture (share) karne ki permission
     * — warna AudioPlaybackCapture khaali signal deta hai.
     */
    private static AudioAttributes buildAttrs() {
        final AudioAttributes.Builder b = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        try {
            // Android 10+: is playback ko call me capture karne ki permission.
            // Zaroori: ye Builder ka method hai (AudioAttributes instance pe nahi).
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                b.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
            }
        } catch (Throwable ignore) {
        }
        return b.build();
    }

    private void stopInternal() {
        streamUrl = null;
        prepared = false;
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Throwable ignore) {
        }
        player = null;
        detachEffects();
    }

    /* --------------------------- equalizer --------------------------- */

    private void attachEffects() {
        if (player == null) return;
        detachEffects();
        final int session = player.getAudioSessionId();
        if (session == AudioAttributes.USAGE_UNKNOWN) return;
        try {
            equalizer = new Equalizer(0, session);
            equalizer.setEnabled(true);
        } catch (Throwable t) {
            equalizer = null;
        }
        try {
            bassBoost = new BassBoost(0, session);
            bassBoost.setEnabled(true);
        } catch (Throwable t) {
            bassBoost = null;
        }
        applyEqPreset(eqPreset);
    }

    private void detachEffects() {
        try {
            if (equalizer != null) { equalizer.setEnabled(false); equalizer.release(); }
        } catch (Throwable ignore) {
        }
        equalizer = null;
        try {
            if (bassBoost != null) { bassBoost.setEnabled(false); bassBoost.release(); }
        } catch (Throwable ignore) {
        }
        bassBoost = null;
    }

    /** Playback EQ presets — 5 band levels (millibels). */
    public void applyEqPreset(int preset) {
        eqPreset = preset;
        if (equalizer == null) return;
        try {
            final short bands = equalizer.getNumberOfBands();
            final short min = equalizer.getBandLevelRange()[0];
            final short max = equalizer.getBandLevelRange()[1];
            // [-1 .. +1] weights per band (askari sa presets)
            float[] w;
            switch (preset) {
                case EQ_BASS:      w = new float[]{1.0f, 0.8f, 0.1f, -0.2f, -0.4f}; break;
                case EQ_VOCAL:     w = new float[]{-0.6f, -0.2f, 0.5f, 0.9f, 0.3f}; break;
                case EQ_LOUDNESS:  w = new float[]{0.8f, 0.6f, 0.7f, 0.8f, 0.9f}; break;
                default:           w = new float[]{0f, 0f, 0f, 0f, 0f}; break;
            }
            for (short b = 0; b < bands; b++) {
                final float weight = w[Math.min(w.length - 1, b * w.length / Math.max(1, bands))];
                final short level = (short) (weight > 0 ? weight * max : weight * -min);
                equalizer.setBandLevel(b, level);
            }
            if (bassBoost != null) {
                bassBoost.setEnabled(preset == EQ_BASS || preset == EQ_LOUDNESS);
                if (preset == EQ_BASS || preset == EQ_LOUDNESS) {
                    bassBoost.setStrength((short) Math.min(1000, preset == EQ_BASS ? 800 : 500));
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private void notifyState(String error) {
        // music-share (call me gaana) ko batao — decode mode me naya URL chahiye hota hai
        try { MusicShare.onPlayerState(appContext); } catch (Throwable ignore) {}
        final Listener l = listener;
        if (l != null) l.onState(isPlaying(), current, error);
    }
}
