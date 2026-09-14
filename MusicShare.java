package org.telegram.messenger.gramify;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * MusicShare — "call me gaana dono ko sunai de" ka dimaag.
 *
 * <h3>Final flow (ye hi device pe chalta hai)</h3>
 * <pre>
 *   Aap: Music tab se gaana chalao ──► aap speaker/headphone pe sunte ho
 *
 *   MusicShare switch ON ──► gaana outgoing voice me DIGITALLY mix hota hai
 *                                     │
 *                                     └──► saamne wala bhi sunta hai (dono!)
 * </pre>
 *
 * Do engines, phone ke hisaab se:
 * <table>
 *   <tr><th>Mode</th><th>Kab</th><th>Kaise</th><th>Consent</th></tr>
 *   <tr><td>{@code decode}</td><td>DEFAULT — sab phones (Android 5+)</td>
 *       <td>{@link CallMusicDecoder}: gaana download → MP4/AAC decode → PCM ring →
 *           mic PCM me mix</td><td>nahi</td></tr>
 *   <tr><td>{@code capture}</td><td>HD mode (Android 10+)</td>
 *       <td>{@link CallMusicCapture}: system playback capture, zero extra data,
 *           aap jo sun rahe ho bilkul wahi jaata hai</td><td>ek baar</td></tr>
 * </table>
 *
 * <h3>Call-aware</h3>
 * Engine sirf <b>call ke andar</b> chalta hai — warna battery aur "recording" indicator
 * faaltu chalta rehta. Switch ON rakho: agli call me apne aap lag jayega.
 */
public final class MusicShare implements VoiceEnhancer.MuteListener {

    public static final String MODE_OFF = "off";
    public static final String MODE_CAPTURE = "capture";
    public static final String MODE_DECODE = "decode";

    private static volatile boolean enabled = false;     // user ka switch (persist hota hai)
    private static volatile boolean inCall = false;      // VoIPService se
    private static volatile boolean running = false;     // actual engine chal raha?
    private static volatile boolean hdPreferred = false; // HD mode chuna gaya?
    private static volatile String mode = MODE_OFF;
    private static volatile String decodeUrl;
    private static volatile boolean micMuted = false;

    private MusicShare() {}

    /* ------------------------------------------------------------------ *
     *  MIC MUTE — "user mute ho gaya to song band ho jayega"
     * ------------------------------------------------------------------ */

    /**
     * VoiceEnhancer (audio thread) se call hota hai jab Telegram ka mic-mute flag badalta hai.
     * Mute = aapki awaaz bhi nahi jaati, to gaana bhejne ka koi matlab nahi —
     * engine pause kar dete hain. Unmute karte hi (call me hote hue) apne aap wapas chalu.
     */
    @Override
    public void onMicMuteChanged(boolean muted) {
        micMuted = muted;
        if (muted) {
            CallMusicCapture.stop();
            CallMusicDecoder.stop();
            CallMixer.setActive(false);
            CallMixer.clear();          // backlog saaf — unmute pe purana gaana na aaye
            running = false;
            if (enabled) mode = "mic muted";
        } else if (enabled && inCall) {
            startEngine(LastContextHolder.ctx);
        }
    }

    public static boolean isMicMuted() { return micMuted; }

    /** ApplicationLoader / fragment se app context yaad rakhte hain (mute-unmute ke liye zaroori). */
    private static final class LastContextHolder {
        static Context ctx;
    }

    public static boolean isEnabled() { return enabled; }

    public static boolean isRunning() { return running; }

    public static boolean isHdPreferred() { return hdPreferred; }

    public static String getMode() { return mode; }

    /* ------------------------------------------------------------------ *
     *  user switch
     * ------------------------------------------------------------------ */

    public static synchronized boolean setEnabled(Context context, boolean on) {
        if (context != null) LastContextHolder.ctx = context.getApplicationContext();
        VoiceEnhancer.setMuteListener(new MusicShare());   // mute -> song band
        enabled = on;
        if (on) {
            if (inCall) {
                return startEngine(context);
            }
            mode = "waiting for call";
            return true;   // call shuru hote hi apne aap chalu
        }
        stopEngine();
        mode = MODE_OFF;
        return false;
    }

    /* ------------------------------------------------------------------ *
     *  HD capture (optional, consent wala)
     * ------------------------------------------------------------------ */

    /** HD mode ON karna hai to ye intent launch karo (startActivityForResult). */
    public static Intent createHdConsentIntent(Context context) {
        hdPreferred = true;
        return CallMusicCapture.createConsentIntent(context);
    }

    /** onActivityResult se aaya result. */
    public static synchronized void onHdConsentResult(Context context, int resultCode, Intent data) {
        final boolean ok = CallMusicCapture.onConsentResult(context, resultCode, data);
        if (!ok) {
            hdPreferred = false;
            // HD nahi mila to decode pe chalte raho — feature band nahi hona chahiye
            if (enabled && inCall) {
                stopEngine();
                startEngine(context);
            }
            return;
        }
        if (enabled && inCall) {
            stopEngine();
            startEngine(context);   // ab capture mode me
        }
    }

    public static synchronized void disableHd(Context context) {
        hdPreferred = false;
        CallMusicCapture.releaseProjection();
        if (enabled && inCall) {
            stopEngine();
            startEngine(context);
        } else {
            mode = enabled ? "waiting for call" : MODE_OFF;
        }
    }

    /* ------------------------------------------------------------------ *
     *  call state (VoIPService se)
     * ------------------------------------------------------------------ */

    public static synchronized void setInCall(Context context, boolean value) {
        if (context != null) LastContextHolder.ctx = context.getApplicationContext();
        VoiceEnhancer.setMuteListener(new MusicShare());
        inCall = value;
        if (!value) {
            stopEngine();
            if (enabled) mode = "waiting for call";
            return;
        }
        if (enabled) {
            startEngine(context);
        }
    }

    /* ------------------------------------------------------------------ *
     *  engine
     * ------------------------------------------------------------------ */

    private static boolean startEngine(Context context) {
        if (running) return true;
        if (context == null) return false;

        // 1) HD capture (agar user ne consent diya hua hai)
        if (hdPreferred && CallMusicCapture.isSupported() && CallMusicCapture.isReady()) {
            if (CallMusicCapture.start()) {
                running = true;
                mode = MODE_CAPTURE;
                CallMixer.setActive(true);
                return true;
            }
            // capture fail → decode pe
        }

        // 2) default: decode (sab phones, koi consent nahi)
        final String url = GramifyPlayer.get().getStreamUrl();
        if (url != null && !url.isEmpty()) {
            CallMusicDecoder.start(context, url);
            decodeUrl = url;
            running = true;
            mode = MODE_DECODE;
            CallMixer.setActive(true);
            return true;
        }

        // gaana abhi nahi chal raha — engine ready rakho, jab chalao tab mix hoga
        running = true;
        mode = MODE_DECODE;
        CallMixer.setActive(true);
        return true;
    }

    private static void stopEngine() {
        CallMusicCapture.stop();
        CallMusicDecoder.stop();
        CallMixer.setActive(false);
        CallMixer.clear();
        decodeUrl = null;
        running = false;
    }

    /** Gaana badla / chala / ruka — decode mode me decoder ko naye URL pe le jao. */
    public static synchronized void onPlayerState(Context context) {
        if (!enabled || !inCall) return;
        if (MODE_DECODE.equals(mode)) {
            if (context == null) return;
            final String url = GramifyPlayer.get().getStreamUrl();
            if (url == null) {
                CallMusicDecoder.stop();
                return;
            }
            // naya gaana HO ya purana gaana dobara chala HO — decoder fresh chahiye
            if (!url.equals(decodeUrl) || !CallMusicDecoder.isRunning()) {
                CallMusicDecoder.start(context, url);
                decodeUrl = url;
            }
        }
        // capture mode me kuch karne ki zaroorat nahi — system khud utha leta hai
    }

    /** App start pe pref restore. */
    public static synchronized void restore(Context context) {
        if (context == null) return;
        LastContextHolder.ctx = context.getApplicationContext();
        VoiceEnhancer.setMuteListener(new MusicShare());
        final boolean on = context.getSharedPreferences("gramify_voice", Context.MODE_PRIVATE)
                .getBoolean("music_share", false);
        if (on) setEnabled(context, true);
    }

    /* ------------------------------------------------------------------ *
     *  UI strings
     * ------------------------------------------------------------------ */

    public static String statusLine() {
        if (!enabled) return "OFF — gaana sirf aap sunoge";
        if (micMuted) return "Mic MUTE hai — gaana band (unmute karte hi wapas chalega)";
        if (!inCall) return "ON — call shuru hote hi gaana dono ko sunai dega";
        if ("waiting for call".equals(mode)) return "ON — call shuru hote hi gaana dono ko sunai dega";
        if (MODE_CAPTURE.equals(mode)) {
            return "ON (HD capture) — saamne wala bhi sun raha hai"
                    + (CallMusicCapture.getLevel() > 0.01f ? " • signal ✓" : " • gaana chalao…");
        }
        if (MODE_DECODE.equals(mode)) {
            return "ON (decode) — saamne wala bhi sun raha hai • " + CallMusicDecoder.getStatus();
        }
        return "ON — " + mode;
    }

    public static String debugLine() {
        return "mixed=" + CallMixer.getMixedFrames() + " frames • ring=" + CallMixer.availableFrames()
                + " • " + (MODE_CAPTURE.equals(mode)
                ? ("capture lvl=" + fmt(CallMusicCapture.getLevel()))
                : ("decoded=" + CallMusicDecoder.getDecodedFrames()))
                + " • sdk=" + Build.VERSION.SDK_INT + " • mic=" + fmt(VoiceEnhancer.getLastInRms());
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
