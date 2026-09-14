package org.telegram.messenger.gramify;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/**
 * GramifyBubble — ek hi floating bubble, do panels ke saath:
 *   1) VOICE / MUSIC  — real-time equalizer + mic boost + music in call
 *   2) BOT MANAGER    — group link, name change, message send (DevgramBotPanel)
 *
 * Bubble ko drag karo, tap karo -> panel khulta hai.
 * Panel ke BAHAR kahin bhi tap -> panel band (bubble wapas).
 * Bubble khud kabhi gayab nahi hota — sirf service stop karne pe chhupata hai.
 *
 * Window type: TYPE_APPLICATION_OVERLAY (8+) / TYPE_PHONE (purane)
 * — isliye "Display over other apps" permission chahiye.
 */
public final class GramifyBubble {

    private static final int ACCENT = 0xFF2AABEE;   // Telegram blue
    private static final int ACCENT_2 = 0xFF229ED9;
    private static final int BG = 0xF217212B;       // dark card
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GRAY = 0xFF8B98A5;
    private static final int GREEN = 0xFF4AD07A;

    private final Context context;
    private final WindowManager windowManager;

    // ---- bubble window ----
    private FrameLayout bubbleRoot;
    private WindowManager.LayoutParams bubbleLp;
    private boolean dragged = false;
    private float downX, downY;
    private int startX, startY;

    // ---- panel window ----
    private View panelView;
    private WindowManager.LayoutParams panelLp;
    private boolean panelOpen = false;

    private LinearLayout musicPanel;
    private DevgramBotPanel botPanel;
    private TextView tabVoice, tabBot;

    // ---- music/EQ live fields ----
    private TextView statusText;
    private TextView musicStatus;
    private ProgressBar inMeter, outMeter;
    private View[] chips;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            handler.postDelayed(this, 250);
        }
    };

    public GramifyBubble(Context context, WindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager != null ? windowManager
                : (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /* ------------------------------------------------------------------ *
     *  show / hide
     * ------------------------------------------------------------------ */

    public void show() {
        if (bubbleRoot != null) return;
        bubbleRoot = new FrameLayout(context);
        bubbleRoot.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubbleRoot.addView(buildBubble());

        int type = overlayType();
        bubbleLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = dp(8);
        bubbleLp.y = dp(120);

        try {
            windowManager.addView(bubbleRoot, bubbleLp);
            handler.post(tick);
        } catch (Throwable t) {
            bubbleRoot = null;
        }
    }

    public void hide() {
        closePanel();
        handler.removeCallbacks(tick);
        final FrameLayout r = bubbleRoot;
        bubbleRoot = null;
        if (r != null) {
            try { windowManager.removeView(r); } catch (Throwable ignore) {}
        }
    }

    public boolean isShown() { return bubbleRoot != null; }
    public boolean isPanelOpen() { return panelOpen; }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void updateWindow() {
        try {
            if (bubbleRoot != null) windowManager.updateViewLayout(bubbleRoot, bubbleLp);
        } catch (Throwable ignore) {}
    }

    /* ------------------------------------------------------------------ *
     *  round bubble
     * ------------------------------------------------------------------ */

    private View buildBubble() {
        final FrameLayout holder = new FrameLayout(context);
        final int size = dp(56);
        holder.setLayoutParams(new FrameLayout.LayoutParams(size, size));

        final GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT_2});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(2), 0x66FFFFFF);

        final ImageView icon = new ImageView(context);
        try { icon.setImageResource(R.drawable.gramify_mic); } catch (Throwable ignore) {}
        icon.setColorFilter(Color.WHITE);
        icon.setPadding(dp(14), dp(14), dp(14), dp(14));
        holder.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        holder.setBackground(bg);
        holder.setElevation(dp(8));

        holder.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bubbleLp.x;
                    startY = bubbleLp.y;
                    dragged = false;
                    v.setAlpha(0.75f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    final int dx = (int) (e.getRawX() - downX);
                    final int dy = (int) (e.getRawY() - downY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) dragged = true;
                    if (dragged) {
                        bubbleLp.x = startX + dx;
                        bubbleLp.y = startY + dy;
                        updateWindow();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    if (!dragged) togglePanel();
                    return true;
                default:
                    return false;
            }
        });

        holder.setOnLongClickListener(v -> {
            Toast.makeText(context, "Bubble hide — Settings se dobara ON kar sakte ho",
                    Toast.LENGTH_SHORT).show();
            GramifyBubbleService.stop(context);
            return true;
        });
        return holder;
    }

    /* ------------------------------------------------------------------ *
     *  panel window (music/EQ + bot, outside tap = close)
     * ------------------------------------------------------------------ */

    private void togglePanel() {
        if (panelOpen) closePanel();
        else openPanel();
    }

    private void openPanel() {
        if (panelOpen) return;
        try {
            if (panelView == null) {
                final LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setBackground(round(BG, dp(16), 0x33000000, dp(1)));
                container.setPadding(dp(14), dp(12), dp(14), dp(12));
                container.setElevation(dp(10));

                // ---- tabs ----
                final LinearLayout tabs = new LinearLayout(context);
                tabs.setOrientation(LinearLayout.HORIZONTAL);
                tabVoice = button("Voice / Music", v -> showMusicTab());
                tabBot = button("Bot Manager", v -> showBotTab());
                tabs.addView(tabVoice, weight());
                tabs.addView(tabBot, weight());
                container.addView(tabs);

                // ---- content ----
                final FrameLayout content = new FrameLayout(context);
                musicPanel = buildMusicPanel();
                botPanel = new DevgramBotPanel(context, this::closePanel);
                botPanel.setVisibility(View.GONE);
                content.addView(musicPanel, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                content.addView(botPanel, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                container.addView(content);

                panelView = container;

                final int type = overlayType();
                panelLp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        android.graphics.PixelFormat.TRANSLUCENT);
                panelLp.gravity = Gravity.CENTER;
                panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

                // panel ke BAHAR tap -> band (bubble wapas)
                panelView.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                        closePanel();
                        return true;
                    }
                    return false;
                });

                highlightTab(true);
            }

            windowManager.addView(panelView, panelLp);
            panelOpen = true;
            updateChips();
        } catch (Throwable ignore) {}
    }

    public void closePanel() {
        if (!panelOpen || panelView == null) return;
        try { windowManager.removeView(panelView); } catch (Throwable ignore) {}
        panelOpen = false;
    }

    private void showMusicTab() {
        if (musicPanel != null) musicPanel.setVisibility(View.VISIBLE);
        if (botPanel != null) botPanel.setVisibility(View.GONE);
        highlightTab(true);
    }

    private void showBotTab() {
        if (musicPanel != null) musicPanel.setVisibility(View.GONE);
        if (botPanel != null) botPanel.setVisibility(View.VISIBLE);
        highlightTab(false);
    }

    private void highlightTab(boolean voice) {
        if (tabVoice != null) {
            tabVoice.setTextColor(voice ? 0xFF17212B : TEXT);
            tabVoice.setBackground(round(voice ? GREEN : 0x33FFFFFF, dp(14),
                    voice ? GREEN : 0x44FFFFFF, dp(1)));
        }
        if (tabBot != null) {
            tabBot.setTextColor(voice ? TEXT : 0xFF17212B);
            tabBot.setBackground(round(voice ? 0x33FFFFFF : GREEN, dp(14),
                    voice ? 0x44FFFFFF : GREEN, dp(1)));
        }
    }

    /* ------------------------------------------------------------------ *
     *  panel — VOICE / MUSIC (real-time EQ + music in call)
     * ------------------------------------------------------------------ */

    private LinearLayout buildMusicPanel() {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(context);
        title.setText("DEVGRAM REMASTERED");
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(title);

        statusText = new TextView(context);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setTextColor(GRAY);
        card.addView(statusText);

        // ---- master switch ----
        final Switch master = new Switch(context);
        master.setText("Voice enhancer (call me saamne wala sunega)");
        master.setTextColor(TEXT);
        master.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        master.setChecked(VoiceEnhancer.isEnabled());
        master.setOnCheckedChangeListener((b, on) -> {
            VoiceEnhancer.setEnabled(context, on);
            updateLive();
        });
        card.addView(master);

        // ---- preset chips (A-Z saare modes) ----
        final TextView modeLabel = new TextView(context);
        modeLabel.setText("MODE / EQ");
        modeLabel.setTextColor(ACCENT);
        modeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        modeLabel.setPadding(0, dp(8), 0, dp(4));
        card.addView(modeLabel);

        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chips = new View[VoiceDsp.PRESET_COUNT];
        for (int p = 0; p < VoiceDsp.PRESET_COUNT; p++) {
            final int preset = p;
            final TextView chip = new TextView(context);
            chip.setText(VoiceDsp.PRESET_NAME[p]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setOnClickListener(v -> {
                VoiceEnhancer.setPreset(context, preset);
                updateChips();
                updateLive();
                Toast.makeText(context, VoiceDsp.PRESET_NAME[preset] + " — "
                        + VoiceDsp.PRESET_DESC[preset], Toast.LENGTH_SHORT).show();
            });
            final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(6);
            chipsRow.addView(chip, clp);
            chips[p] = chip;
        }
        chipsScroll.addView(chipsRow);
        card.addView(chipsScroll);

        // ---- sliders (max limits tak) ----
        addSlider(card, "Mic boost (dB)", 0f, 36f, VoiceEnhancer.getInputGainDb(), " dB",
                (v) -> VoiceEnhancer.setInputGainDb(context, v));
        addSlider(card, "Loudness x", 1f, 8f, VoiceEnhancer.getLoudness(), "x",
                (v) -> VoiceEnhancer.setLoudness(context, v));
        addSlider(card, "Drive (saturation)", 0f, 1f, VoiceEnhancer.getDrive(), "",
                (v) -> VoiceEnhancer.setDrive(context, v));
        addSlider(card, "Presence 3.2 kHz (dB)", -18f, 18f, VoiceEnhancer.getPresenceDb(), " dB",
                (v) -> VoiceEnhancer.setPresenceDb(context, v));

        // ---- meters ----
        final LinearLayout meters = new LinearLayout(context);
        meters.setOrientation(LinearLayout.VERTICAL);
        meters.setPadding(0, dp(8), 0, 0);
        inMeter = meterRow(meters, "MIC", ACCENT);
        outMeter = meterRow(meters, "OUTGOING", GREEN);
        card.addView(meters);

        // ---- music ----
        final TextView musicLabel = new TextView(context);
        musicLabel.setText("MUSIC IN CALL — dono ko sunai de");
        musicLabel.setTextColor(GREEN);
        musicLabel.setTypeface(Typeface.DEFAULT_BOLD);
        musicLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        musicLabel.setPadding(0, dp(10), 0, dp(4));
        card.addView(musicLabel);

        final Switch music = new Switch(context);
        music.setText("Gaana call me bhejo");
        music.setTextColor(TEXT);
        music.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        music.setChecked(MusicShare.isEnabled());
        music.setOnCheckedChangeListener((b, on) -> {
            context.getSharedPreferences("gramify_voice", Context.MODE_PRIVATE)
                    .edit().putBoolean("music_share", on).apply();
            MusicShare.setEnabled(context, on);
            updateLive();
        });
        card.addView(music);

        addSlider(card, "Music volume", 0f, 1.5f, VoiceEnhancer.getMusicVolume(), "",
                (v) -> VoiceEnhancer.setMusicVolume(context, v));

        musicStatus = new TextView(context);
        musicStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        musicStatus.setTextColor(GRAY);
        card.addView(musicStatus);

        // ---- buttons ----
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        buttons.addView(button("Open Devgram", v -> openApp()), weight());
        buttons.addView(button("Hide bubble", v -> GramifyBubbleService.stop(context)), weight());
        card.addView(buttons);

        return card;
    }

    private LinearLayout.LayoutParams weight() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        return lp;
    }

    private TextView button(String text, View.OnClickListener click) {
        final TextView b = new TextView(context);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setBackground(round(0x33FFFFFF, dp(10), 0x44FFFFFF, dp(1)));
        b.setOnClickListener(click);
        return b;
    }

    /** label + value + seekbar (value live dikhti hai) */
    private void addSlider(LinearLayout parent, String name, float min, float max, float value,
                           String unit, final FloatSetter setter) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);

        final TextView label = new TextView(context);
        label.setText(name + ": " + fmt(value) + unit);
        label.setTextColor(TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        box.addView(label);

        final SeekBar bar = new SeekBar(context);
        bar.setMax(1000);
        bar.setProgress(toProgress(value, min, max));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                final float v = min + (max - min) * (progress / 1000f);
                label.setText(name + ": " + fmt(v) + unit);
                setter.set(v);
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(bar);
        parent.addView(box);
    }

    private ProgressBar meterRow(LinearLayout parent, String label, int color) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView t = new TextView(context);
        t.setText(label);
        t.setTextColor(GRAY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setWidth(dp(64));
        row.addView(t);

        final ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        row.addView(bar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return bar;
    }

    /* ------------------------------------------------------------------ *
     *  live update
     * ------------------------------------------------------------------ */

    private void updateLive() {
        if (inMeter != null) {
            final float in = VoiceEnhancer.getLastInRms();
            inMeter.setProgress(Math.min(100, (int) (in * 300)));
        }
        if (outMeter != null) {
            final float out = VoiceEnhancer.getLastOutRms();
            outMeter.setProgress(Math.min(100, (int) (out * 300)));
        }
        if (statusText != null) {
            final boolean on = VoiceEnhancer.isEnabled();
            statusText.setText((on ? "LIVE" : "OFF")
                    + " • " + VoiceDsp.PRESET_NAME[Math.min(VoiceDsp.PRESET_COUNT - 1,
                    Math.max(0, VoiceEnhancer.getPreset()))]
                    + " • " + (VoiceEnhancer.isHookAlive() ? "call me chal raha hai" : "call ka wait")
                    + " • " + Math.round(VoiceEnhancer.getInputGainDb()) + " dB");
            statusText.setTextColor(on ? (VoiceEnhancer.isHookAlive() ? GREEN : ACCENT) : GRAY);
        }
        if (musicStatus != null) {
            musicStatus.setText(MusicShare.statusLine());
            musicStatus.setTextColor(MusicShare.isRunning() ? GREEN : GRAY);
        }
    }

    private void updateChips() {
        if (chips == null) return;
        final int current = VoiceEnhancer.getPreset();
        for (int p = 0; p < chips.length; p++) {
            final boolean sel = p == current;
            final TextView c = (TextView) chips[p];
            c.setTextColor(sel ? 0xFF17212B : TEXT);
            c.setBackground(round(sel ? GREEN : 0x33FFFFFF, dp(14),
                    sel ? GREEN : 0x44FFFFFF, dp(1)));
        }
    }

    /* ------------------------------------------------------------------ */

    private void openApp() {
        try {
            final Intent i = new Intent(context, org.telegram.ui.LaunchActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(context, "App kholo → Settings → Devgram Voice", Toast.LENGTH_LONG).show();
        }
    }

    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static int toProgress(float v, float min, float max) {
        if (max <= min) return 0;
        return (int) (1000f * (v - min) / (max - min));
    }

    private interface FloatSetter {
        void set(float v);
    }
}
