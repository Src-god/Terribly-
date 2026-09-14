package org.telegram.messenger.gramify;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * GramifyBubbleService — screen pe tairta hua round bubble dikhata hai.
 *
 * <p>Bubble me <b>dono functions</b> hain (voice enhancer + music in call), aur wahi
 * settings screen me bhi milte hain — dono ek hi {@link VoiceEnhancer} / {@link MusicShare}
 * par likhte hain, isliye jagah koi bhi ho, asar same hota hai.</p>
 *
 * <p>Zaroori permission: "Display over other apps" (SYSTEM_ALERT_WINDOW).
 * Settings → Devgram Voice → "Floating bubble" switch ON karne pe app khud permission
 * maangta hai; milne ke baad ye service bubble dikha deti hai.</p>
 *
 * <p>Bubble call ke dauraan bhi dikhta rehta hai (Telegram ke upar), isliye call me
 * ek tap me preset badal sakte ho — "MAX POWER" bhi.</p>
 */
public class GramifyBubbleService extends Service {

    public static final String ACTION_SHOW = "org.telegram.messenger.gramify.BUBBLE_SHOW";
    public static final String ACTION_HIDE = "org.telegram.messenger.gramify.BUBBLE_HIDE";
    public static final String PREF = "gramify_voice";
    public static final String PREF_BUBBLE = "bubble_on";

    private static volatile boolean running = false;

    private WindowManager windowManager;
    private GramifyBubble bubble;

    public static boolean isRunning() { return running; }

    public static boolean isEnabled(Context ctx) {
        return ctx != null && ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(PREF_BUBBLE, false);
    }

    public static boolean canShow(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bubble ON karo (permission ho to turant dikhega). */
    public static void show(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx, GramifyBubbleService.class).setAction(ACTION_SHOW));
        } catch (Throwable t) {
            Toast.makeText(ctx, "Bubble start nahi hui: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Bubble OFF (pref bhi clear). */
    public static void stop(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_BUBBLE, false).apply();
        try {
            ctx.startService(new Intent(ctx, GramifyBubbleService.class).setAction(ACTION_HIDE));
        } catch (Throwable ignore) {
        }
    }

    /* ------------------------------------------------------------------ */

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Note: mute -> "song band" ka listener MusicShare khud lagata hai
        // (ApplicationLoader ke boot hook / toggle se), isliye yahan dobara nahi lagate.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : ACTION_SHOW;

        if (ACTION_HIDE.equals(action)) {
            hideBubble();
            stopSelf();
            return START_NOT_STICKY;
        }

        // SHOW (ya null intent = sticky restart)
        if (!canShow(this)) {
            Toast.makeText(this,
                    "Bubble ke liye permission do: Settings → Apps → Devgram → Display over other apps",
                    Toast.LENGTH_LONG).show();
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(PREF_BUBBLE, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(PREF_BUBBLE, true).apply();
        showBubble();
        return START_STICKY;   // system kill kare to wapas aa jaye
    }

    private void showBubble() {
        if (bubble != null && bubble.isShown()) return;
        try {
            bubble = new GramifyBubble(this, windowManager);
            bubble.show();
            running = bubble.isShown();
        } catch (Throwable t) {
            running = false;
            Toast.makeText(this, "Bubble nahi dikh paayi: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void hideBubble() {
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }
        running = false;
    }

    @Override
    public void onDestroy() {
        hideBubble();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
