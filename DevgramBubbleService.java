package org.telegram.messenger.gramify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * DevgramBubbleService — foreground service for the floating bubble.
 */
public class DevgramBubbleService extends Service {

    private static final String CHANNEL_ID = "devgram_bubble_channel";
    private static final int NOTIF_ID = 7788;

    private GramifyBubble bubble;

    @Override
    public void onCreate() {
        super.onCreate();
        try { startForeground(NOTIF_ID, buildNotification()); } catch (Throwable ignore) {}
        bubble = new GramifyBubble(this, null);
        bubble.show();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Devgram Bubble", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Devgram Bot Active")
                .setContentText("Tap bubble to open panel")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (bubble == null) {
            bubble = new GramifyBubble(this, null);
            bubble.show();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
