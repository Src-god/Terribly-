package org.telegram.messenger.gramify;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;

/**
 * GramifyBubble — Devgram floating bubble
 * - Single bubble, tap → panel open
 * - Panel ke bahar tap → panel collapse, bubble wapas
 * - Bubble tap wapas → panel reopen
 * - Long press → voice enhancer panel
 */
public class GramifyBubble {

    private final Context context;
    private final WindowManager wm;
    private FrameLayout bubble;
    private WindowManager.LayoutParams bubbleLp;

    private View panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelOpen = false;

    private final Runnable onVoiceLongPress;

    public GramifyBubble(Context ctx, Runnable onVoiceLongPress) {
        this.context = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.onVoiceLongPress = onVoiceLongPress;
    }

    public boolean isPanelOpen() { return panelOpen; }

    public void show() {
        if (bubble != null) return;
        int size = dp(56);
        bubble = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF0111111);
        bg.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(bg);

        TextView icon = new TextView(context);
        icon.setText("🫍");
        icon.setTextSize(26);
        icon.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(size, size);
        ilp.gravity = Gravity.CENTER;
        icon.setLayoutParams(ilp);
        bubble.addView(icon);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleLp = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = dp(16);
        bubbleLp.y = dp(220);

        bubble.setOnTouchListener(new DragListener());
        bubble.setOnClickListener(v -> togglePanel());
        bubble.setOnLongClickListener(v -> {
            if (onVoiceLongPress != null) onVoiceLongPress.run();
            return true;
        });

        try { wm.addView(bubble, bubbleLp); } catch (Throwable ignore) {}
    }

    public void hide() {
        closePanel();
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Throwable ignore) {}
            bubble = null;
        }
    }

    // ---------------- Panel ----------------
    private void togglePanel() {
        if (panelOpen) closePanel();
        else openPanel();
    }

    private void openPanel() {
        if (panelOpen) return;
        try {
            DevgramBotPanel botPanel = new DevgramBotPanel(context, this::closePanel);
            panel = botPanel;

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            panelLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            panelLp.gravity = Gravity.CENTER;
            panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            panel.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    closePanel();
                    return true;
                }
                return false;
            });

            wm.addView(panel, panelLp);
            panelOpen = true;
        } catch (Throwable ignore) {}
    }

    public void closePanel() {
        if (!panelOpen || panel == null) return;
        try { wm.removeView(panel); } catch (Throwable ignore) {}
        panel = null;
        panelOpen = false;
    }

    /** Called from ACTION_OUTSIDE or external taps. */
    private void hidePanelOrCollapse() {
        if (panelOpen) closePanel();
        else show();
    }

    public void onBubbleTap() {
        togglePanel();
    }

    // ---------------- Drag ----------------
    @SuppressLint("ClickableViewAccessibility")
    private class DragListener implements View.OnTouchListener {
        private int initX, initY;
        private float touchX, touchY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initX = bubbleLp.x;
                    initY = bubbleLp.y;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - touchX);
                    int dy = (int) (e.getRawY() - touchY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    bubbleLp.x = initX + dx;
                    bubbleLp.y = initY + dy;
                    try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignore) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) v.performClick();
                    return true;
            }
            return false;
        }
    }

    private int dp(int v) { return AndroidUtilities.dp(v); }
}
