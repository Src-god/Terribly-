package org.telegram.messenger.gramify;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * CallMusicCapture — "HD mode": Android 10+ ka system playback capture.
 *
 * <h3>Kaam kaise karta hai</h3>
 * <pre>
 *  JioSaavn song (MediaPlayer) ──► speaker/headphone        (aap sunte hain)
 *            │
 *            └──► MediaProjection + AudioPlaybackCapture ──► CallMixer ring
 *                                    │
 *                                    └──► outgoing PCM me mix ──► saamne wala sunta hai
 * </pre>
 *
 * <h3>Zaroori sach</h3>
 * Android ka {@code AudioPlaybackCaptureConfiguration.Builder} ko ek
 * <b>MediaProjection</b> chahiye hota hai — yani ek baar system ka consent dialog
 * ("Start recording or casting?") allow karna padta hai. Isliye ye <b>optional HD mode</b>
 * hai ({@link MusicShare} dekho). Bina consent wala default rasta
 * {@link CallMusicDecoder} hai — wo har phone pe chalta hai.
 *
 * <p>Capture sirf isi app (isi UID) ka audio uthata hai — doosri apps ka audio call me
 * leak nahi hota.</p>
 *
 * <p>Projection aur AudioRecord call ke beech zinda rehte hain (call band hone pe sirf
 * recording rukti hai), taaki har call pe dobara consent na maangna pade.</p>
 */
public final class CallMusicCapture {

    private static final int RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int REQ_CODE = 0x6A17;   // fragment isi code pe result leta hai

    private static volatile boolean running = false;
    private static volatile String status = "off";
    private static volatile long capturedFrames = 0;
    private static volatile float lastLevel = 0f;

    private static Thread thread;
    private static AudioRecord record;
    private static MediaProjection projection;
    private static MediaProjection.Callback projectionCallback;

    private CallMusicCapture() {}

    public static int getRequestCode() { return REQ_CODE; }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** consent mil gaya hai aur record ready hai? */
    public static boolean isReady() {
        return projection != null;
    }

    public static boolean isRunning() { return running; }

    public static String getStatus() { return status; }

    public static long getCapturedFrames() { return capturedFrames; }

    public static float getLevel() { return lastLevel; }

    /* ------------------------------------------------------------------ *
     *  consent
     * ------------------------------------------------------------------ */

    /** UI is intent ko startActivityForResult se launch karta hai. */
    @TargetApi(Build.VERSION_CODES.Q)
    public static Intent createConsentIntent(Context context) {
        if (context == null || !isSupported()) return null;
        try {
            final MediaProjectionManager mgr =
                    (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr == null) return null;
            return mgr.createScreenCaptureIntent();
        } catch (Throwable t) {
            status = "consent intent fail: " + t.getClass().getSimpleName();
            return null;
        }
    }

    /** onActivityResult se: resultCode == Activity.RESULT_OK hona chahiye. */
    @TargetApi(Build.VERSION_CODES.Q)
    public static boolean onConsentResult(Context context, int resultCode, Intent data) {
        if (!isSupported() || context == null) {
            status = "Android 10+ chahiye";
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            status = "consent cancel";
            return false;
        }
        try {
            final MediaProjectionManager mgr =
                    (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                status = "no projection manager";
                return false;
            }
            releaseProjection();
            projection = mgr.getMediaProjection(resultCode, data);
            if (projection == null) {
                status = "projection null";
                return false;
            }
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // system ne rok diya (user ne notification se band kiya)
                    status = "projection stopped";
                    releaseProjection();
                }
            };
            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
            status = "consent OK";
            return true;
        } catch (Throwable t) {
            status = "consent error: " + t.getClass().getSimpleName();
            return false;
        }
    }

    /* ------------------------------------------------------------------ *
     *  capture
     * ------------------------------------------------------------------ */

    @TargetApi(Build.VERSION_CODES.Q)
    private static AudioRecord buildRecord(int bufSize) {
        if (projection == null) return null;

        // capture config:
        //   * sirf HAMARE app ka playback (UID) — doosre apps ka audio call me na jaaye
        //   * lekin call ki awaaz (remote party = USAGE_VOICE_COMMUNICATION) EXCLUDE —
        //     warna hamare hi app ke playback se remote ki awaaz wapas capture ho jaati
        //     aur usko apni awaaz echo sunai deti (feedback loop).
        //     Telegram ka call AudioTrack usage VOICE_COMMUNICATION hi hota hai
        //     (WebRtcAudioTrack.java L74), isliye ye exclusion pakka kaam karta hai.
        final AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUid(Process.myUid())
                        .excludeUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .excludeUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .build();

        final AudioFormat format = new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(RATE)
                .setChannelMask(CHANNEL_MASK)
                .build();

        return new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
    }

    public static synchronized boolean start() {
        if (running) return true;
        if (!isSupported()) {
            status = "Android 10+ chahiye (abhi " + Build.VERSION.RELEASE + ")";
            return false;
        }
        if (projection == null) {
            status = "HD mode ke liye permission chahiye";
            return false;
        }
        try {
            if (record == null) {
                final int minBuf = AudioRecord.getMinBufferSize(RATE, CHANNEL_MASK, ENCODING);
                final int bufSize = Math.max(minBuf, RATE / 5 * 2);   // ~200 ms
                record = buildRecord(bufSize);
                if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                    status = "capture record init fail";
                    if (record != null) {
                        try { record.release(); } catch (Throwable ignore) {}
                        record = null;
                    }
                    return false;
                }
            }

            running = true;
            capturedFrames = 0;
            status = "capturing (HD system capture)";
            thread = new Thread(CallMusicCapture::loop, "GramifyMusicCapture");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            return true;
        } catch (Throwable t) {
            status = "error: " + t.getClass().getSimpleName();
            return false;
        }
    }

    /** call khatam — record rok do, projection zinda rakho (agli call ke liye). */
    public static synchronized void stop() {
        running = false;
        final Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(500); } catch (Throwable ignore) {}
        }
        if (status.startsWith("capturing")) status = "ready (consent OK)";
    }

    /** poora release (app band / user ne HD off kiya). */
    public static synchronized void releaseProjection() {
        stop();
        final AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignore) {}
            try { r.release(); } catch (Throwable ignore) {}
        }
        final MediaProjection p = projection;
        final MediaProjection.Callback cb = projectionCallback;
        projection = null;
        projectionCallback = null;
        if (p != null) {
            try { if (cb != null) p.unregisterCallback(cb); } catch (Throwable ignore) {}
            try { p.stop(); } catch (Throwable ignore) {}
        }
        if (!status.startsWith("capturing")) status = "off";
    }

    private static void loop() {
        final AudioRecord r = record;
        if (r == null) return;
        final short[] buf = new short[960];     // 20 ms @ 48k mono
        try {
            r.startRecording();
        } catch (Throwable t) {
            status = "startRecording fail: " + t.getClass().getSimpleName();
            running = false;
            return;
        }
        while (running && record == r) {
            final int n;
            try {
                n = r.read(buf, 0, buf.length);
            } catch (Throwable t) {
                break;
            }
            if (n <= 0) continue;
            CallMixer.write(buf, 0, n);

            long sum = 0;
            for (int i = 0; i < n; i++) sum += (long) buf[i] * buf[i];
            lastLevel = (float) Math.sqrt(sum / (double) n) / 32768f;
            capturedFrames += n;
        }
        try { r.stop(); } catch (Throwable ignore) {}
    }
}
