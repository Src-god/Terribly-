package org.telegram.messenger.gramify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * Gramify Voice — voice chat me aapki outgoing voice ka enhancer.
 *
 * Yahan jo bhi set karte ho, wo {@link VoiceEnhancer} me save hota hai aur
 * har call/voice-chat me live apply hota hai (10 ms me), bina call restart kiye.
 */
public class VoiceEnhancerFragment extends BaseFragment {

    private static final int ACCENT = 0xFF4A8CFF;

    private int bgColor, textColor, grayColor;
    private ProgressBar inMeter, outMeter;
    private TextView statusText;
    private TextView musicStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable meterTask = new Runnable() {
        @Override
        public void run() {
            final float in = VoiceEnhancer.getLastInRms();
            final float out = VoiceEnhancer.getLastOutRms();
            if (inMeter != null) inMeter.setProgress(Math.min(100, (int) (in * 300)));
            if (outMeter != null) outMeter.setProgress(Math.min(100, (int) (out * 300)));
            if (statusText != null) {
                statusText.setText(VoiceEnhancer.isEnabled()
                        ? ("LIVE (" + VoiceDsp.PRESET_NAME[Math.min(VoiceDsp.PRESET_COUNT - 1,
                        Math.max(0, VoiceEnhancer.getPreset()))] + ") — call me apply ho raha hai"
                        + (VoiceEnhancer.isHookAlive() ? "" : " • call ka wait"))
                        : "OFF — sirf aapki voice, bina change");
            }
            if (musicStatus != null) {
                musicStatus.setText(MusicShare.statusLine() + "\n" + MusicShare.debugLine());
            }
            handler.postDelayed(this, 200);
        }
    };

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle("Gramify Voice");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) finishFragment();
                }
            });
        }

        try {
            bgColor = Theme.getColor(Theme.key_windowBackgroundWhite);
            textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
        } catch (Throwable t) {
            bgColor = Color.WHITE;
            textColor = Color.BLACK;
            grayColor = 0xFF888888;
        }

        VoiceEnhancer.load(context);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        if (actionBar != null) {
            root.addView(actionBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ActionBar.getCurrentActionBarHeight()));
        }

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(content);

        // ----- master switch -----
        final Switch master = new Switch(context);
        master.setText("Voice Enhancer (calls + voice chats)");
        master.setTextColor(textColor);
        master.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        master.setChecked(VoiceEnhancer.isEnabled());
        master.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            VoiceEnhancer.setEnabled(context, on);
            Toast(context, on ? "Enhancer ON — saamne wale ko enhanced voice jayegi"
                    : "Enhancer OFF — normal voice jayegi");
        });
        content.addView(master);

        statusText = new TextView(context);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusText.setTextColor(grayColor);
        statusText.setPadding(0, dp(4), 0, dp(12));
        content.addView(statusText);

        // ----- live meters -----
        content.addView(sectionTitle(context, "MIC (aapki awaaz)"));
        inMeter = meter(context);
        content.addView(inMeter);
        content.addView(sectionTitle(context, "OUTGOING (jo saamne wale ko jayega)"));
        outMeter = meter(context);

        // ----- presets -----
        content.addView(sectionTitle(context, "MODES / EQ (" + VoiceDsp.PRESET_COUNT + " modes)"));
        final LinearLayout presetRow = new LinearLayout(context);
        presetRow.setOrientation(LinearLayout.VERTICAL);
        for (int pid = 0; pid < VoiceDsp.PRESET_COUNT; pid++) {
            final int preset = pid;
            final Button b = new Button(context);
            b.setText(VoiceDsp.PRESET_NAME[pid]);
            b.setAllCaps(false);
            // MAX POWER ko alag rang do — ye "phone-phat" wala mode hai
            if (pid == VoiceDsp.PRESET_MAX_POWER) b.setTextColor(0xFFFF5A5A);
            else if (pid == VoiceDsp.PRESET_LORD || pid == VoiceDsp.PRESET_ROYAL) b.setTextColor(0xFFFFB020);
            b.setOnClickListener(v -> {
                VoiceEnhancer.setPreset(context, preset);
                master.setChecked(VoiceEnhancer.isEnabled());
                Toast(context, VoiceDsp.PRESET_NAME[preset] + " — " + VoiceDsp.PRESET_DESC[preset]);
                // sliders apne aap naye value dikhayenge (screen dobara kholne pe)
            });
            presetRow.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(presetRow);

        // ----- sliders -----
        content.addView(sectionTitle(context, "MANUAL TUNING"));
        addSlider(context, content, "Mic boost", 0, 36, VoiceEnhancer.getInputGainDb(), " dB",
                (v) -> VoiceEnhancer.setInputGainDb(context, v));
        addSlider(context, content, "Loudness x (extra blast)", 1, 8, VoiceEnhancer.getLoudness(), "x",
                (v) -> VoiceEnhancer.setLoudness(context, v));
        addSlider(context, content, "Drive (saturation / warmth)", 0, 1, VoiceEnhancer.getDrive(), "",
                (v) -> VoiceEnhancer.setDrive(context, v));
        addSlider(context, content, "Presence 3.2 kHz", -18, 18, VoiceEnhancer.getPresenceDb(), " dB",
                (v) -> VoiceEnhancer.setPresenceDb(context, v));
        addSlider(context, content, "Output booster", -24, 18, VoiceEnhancer.getOutputGainDb(), " dB",
                (v) -> VoiceEnhancer.setOutputGainDb(context, v));
        addSlider(context, content, "Effect amount (dry/wet)", 0, 1, VoiceEnhancer.getAmount(), "",
                (v) -> VoiceEnhancer.setAmount(context, v));
        addSlider(context, content, "Bass", -12, 12, VoiceEnhancer.getBassDb(), " dB",
                (v) -> VoiceEnhancer.setBassDb(context, v));
        addSlider(context, content, "Treble", -12, 12, VoiceEnhancer.getTrebleDb(), " dB",
                (v) -> VoiceEnhancer.setTrebleDb(context, v));

        content.addView(sectionTitle(context, "5-BAND EQUALISER (voice)"));
        for (int i = 0; i < VoiceDsp.BAND_COUNT; i++) {
            final int band = i;
            addSlider(context, content, VoiceDsp.BAND_NAME[i], -18, 18, VoiceEnhancer.getBandDb(i), " dB",
                    (v) -> VoiceEnhancer.setBandDb(context, band, v));
        }

        // ----- toggles -----
        content.addView(sectionTitle(context, "CLEANUP"));
        final Switch gateSwitch = new Switch(context);
        gateSwitch.setText("Noise gate (fan / AC / typing dabao)");
        gateSwitch.setTextColor(textColor);
        gateSwitch.setChecked(VoiceEnhancer.isGateEnabled());
        gateSwitch.setOnCheckedChangeListener((b, on) -> VoiceEnhancer.setGate(context, on));
        content.addView(gateSwitch);

        final Switch compSwitch = new Switch(context);
        compSwitch.setText("Compressor (awaaz barabar rahe, loud na ho)");
        compSwitch.setTextColor(textColor);
        compSwitch.setChecked(VoiceEnhancer.isCompEnabled());
        compSwitch.setOnCheckedChangeListener((b, on) -> VoiceEnhancer.setComp(context, on));
        content.addView(compSwitch);

        // ----- FLOATING BUBBLE (round bubble — dono functions ek jagah) -----
        content.addView(sectionTitle(context, "FLOATING BUBBLE — call ke dauraan ek tap"));

        final Switch bubbleSwitch = new Switch(context);
        bubbleSwitch.setText("Bubble ON (screen pe round button)");
        bubbleSwitch.setTextColor(textColor);
        bubbleSwitch.setChecked(GramifyBubbleService.isEnabled(context)
                && GramifyBubbleService.canShow(context));
        bubbleSwitch.setOnCheckedChangeListener((b, on) -> {
            if (!on) {
                GramifyBubbleService.stop(context);
                Toast(context, "Bubble band");
                return;
            }
            if (!GramifyBubbleService.canShow(context)) {
                // permission maango — "Display over other apps"
                Toast(context, "Permission do: Display over other apps", android.widget.Toast.LENGTH_LONG);
                try {
                    final Intent i = new Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + context.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                } catch (Throwable t) {
                    Toast(context, "Settings → Apps → Devgram → Display over other apps",
                            android.widget.Toast.LENGTH_LONG);
                }
                b.setChecked(false);
                return;
            }
            GramifyBubbleService.show(context);
            Toast(context, "Bubble ON — call ke dauraan bhi dikhega");
        });
        content.addView(bubbleSwitch);

        final TextView bubbleNote = new TextView(context);
        bubbleNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bubbleNote.setTextColor(grayColor);
        bubbleNote.setPadding(0, 0, 0, dp(6));
        bubbleNote.setText("Bubble me dono functions hain: saare MODES/EQ (Off se MAX POWER tak), "
                + "Mic boost, Loudness, Drive, Presence, meters, aur Music-in-call switch. "
                + "Drag karke kahin bhi rakho. Long-press = hide.\n"
                + "Ek baar 'Display over other apps' permission chahiye (upar wala switch khud maangta hai).");
        content.addView(bubbleNote);

        // ----- MUSIC IN CALL ("dono ko sunai de") -----
        content.addView(sectionTitle(context, "MUSIC IN CALL — dono ko sunai de"));

        final SharedPreferences mp = context.getSharedPreferences("gramify_voice", Context.MODE_PRIVATE);

        final Switch shareSwitch = new Switch(context);
        shareSwitch.setText("Gaana call me bhejo (saamne wala bhi sune)");
        shareSwitch.setTextColor(textColor);
        shareSwitch.setChecked(mp.getBoolean("music_share", false));
        shareSwitch.setOnCheckedChangeListener((b, on) -> {
            mp.edit().putBoolean("music_share", on).apply();
            final boolean ok = MusicShare.setEnabled(context, on);
            if (on && !ok) {
                Toast(context, "Music share start nahi hua: " + MusicShare.getMode());
            } else {
                Toast(context, on ? "Music call me ja raha hai" : "Music ab sirf aap sunoge");
            }
            if (musicStatus != null) musicStatus.setText(MusicShare.statusLine());
        });
        content.addView(shareSwitch);

        final Button hdBtn = new Button(context);
        hdBtn.setText("HD mode: system capture (Android 10+)");
        hdBtn.setAllCaps(false);
        hdBtn.setOnClickListener(v -> {
            if (!CallMusicCapture.isSupported()) {
                Toast(context, "HD mode ke liye Android 10+ chahiye — decode mode already chal raha hai");
                return;
            }
            if (CallMusicCapture.isReady()) {
                MusicShare.disableHd(context);
                Toast(context, "HD mode OFF — decode mode chalu");
                return;
            }
            final Intent intent = MusicShare.createHdConsentIntent(context);
            if (intent == null) {
                Toast(context, "System capture available nahi hai");
                return;
            }
            // system dialog: "Start recording or casting?" -> Allow karo
            try {
                startActivityForResult(intent, CallMusicCapture.getRequestCode());
            } catch (Throwable t) {
                Toast(context, "Consent dialog fail: " + t.getClass().getSimpleName());
            }
        });
        content.addView(hdBtn);

        final TextView hdNote = new TextView(context);
        hdNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hdNote.setTextColor(grayColor);
        hdNote.setPadding(0, 0, 0, dp(6));
        hdNote.setText("HD mode = system ka apna capture (bilkul wahi audio jo aap sun rahe ho, "
                + "koi extra download nahi, perfect sync). Iske liye ek baar Android ka "
                + "\"recording/casting\" permission dena padta hai. "
                + "Normal (decode) mode bina permission har phone pe chalta hai.");
        content.addView(hdNote);

        musicStatus = new TextView(context);
        musicStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        musicStatus.setTextColor(grayColor);
        musicStatus.setPadding(0, dp(4), 0, dp(4));
        musicStatus.setText(MusicShare.statusLine());
        content.addView(musicStatus);

        addSlider(context, content, "Music volume in call", 0f, 1.5f, VoiceEnhancer.getMusicVolume(), "",
                (v) -> VoiceEnhancer.setMusicVolume(context, v));
        addSlider(context, content, "Mic duck (music ke waqt apni awaz kam)", 0f, 1f,
                VoiceEnhancer.getMicDuck(), "",
                (v) -> VoiceEnhancer.setMicDuck(context, v));

        final TextView musicNote = new TextView(context);
        musicNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        musicNote.setTextColor(grayColor);
        musicNote.setPadding(0, dp(4), 0, dp(8));
        musicNote.setText("Kaise chalta hai: gaana aap sunte ho (speaker/headphone) AUR wahi audio "
                + "outgoing voice me mix hoke saamne wale tak bhi jaata hai.\n"
                + "• Android 10+ : system playback capture (best quality, kuch download nahi)\n"
                + "• Android 9-  : gaana decode karke bheja jaata hai\n"
                + "Tip: echo se bachne ke liye headphone use karo, ya 'Mic duck' badha do. "
                + "Music share sirf tab chalta hai jab ye switch ON ho — warna call bilkul normal.");
        content.addView(musicNote);

        final Switch comp2Switch = new Switch(context);
        comp2Switch.setText("Aggressive compressor 2 (reference kit ka comp2)");
        comp2Switch.setTextColor(textColor);
        comp2Switch.setChecked(VoiceEnhancer.isComp2Enabled());
        comp2Switch.setOnCheckedChangeListener((b, on) -> VoiceEnhancer.setComp2(context, on));
        content.addView(comp2Switch);

        final Switch susSwitch = new Switch(context);
        susSwitch.setText("Auto sustain (halki awaaz bhi full volume)");
        susSwitch.setTextColor(textColor);
        susSwitch.setChecked(VoiceEnhancer.isSustainEnabled());
        susSwitch.setOnCheckedChangeListener((b, on) -> VoiceEnhancer.setSustain(context, on));
        content.addView(susSwitch);

        addSlider(context, content, "Sustain target", -40, -3, VoiceEnhancer.getSustainTargetDb(), " dB",
                (v) -> VoiceEnhancer.setSustainTargetDb(context, v));
        addSlider(context, content, "Sustain max gain", 0, 18, VoiceEnhancer.getSustainMaxGainDb(), " dB",
                (v) -> VoiceEnhancer.setSustainMaxGainDb(context, v));
        addSlider(context, content, "Limiter ceiling (kam = zyada tez par saaf)", -6, 0,
                VoiceEnhancer.getLimiterDb(), " dB",
                (v) -> VoiceEnhancer.setLimiterDb(context, v));

        final Button reset = new Button(context);
        reset.setText("Reset to Clear Voice");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> {
            VoiceEnhancer.reset(context);
            master.setChecked(VoiceEnhancer.isEnabled());
            Toast(context, "Reset done");
        });
        content.addView(reset);

        final TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(grayColor);
        note.setPadding(0, dp(12), 0, 0);
        note.setText("Note: ye enhancement aapki voice par lagti hai — jo Telegram bhejta hai "
                + "usse pehle. Saamne wala normal Telegram/phone par bhi enhanced voice sunta hai, "
                + "kyunki processing aapke phone me hi hoti hai. Music playback ka EQ alag hai "
                + "(Gramify Music screen ka EQ button).");
        content.addView(note);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (mp.getBoolean("music_share", false)) {
            MusicShare.setEnabled(context, true);
        }

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == CallMusicCapture.getRequestCode()) {
            MusicShare.onHdConsentResult(getParentActivity(), resultCode, data);
            Toast(getParentActivity(), CallMusicCapture.isReady()
                    ? "HD mode ON — gaana dono ko sunai dega"
                    : "HD mode nahi mila — decode mode chalu hai");
            if (musicStatus != null) musicStatus.setText(MusicShare.statusLine());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(meterTask);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(meterTask);
    }

    /* --------------------------- small helpers --------------------------- */

    private TextView sectionTitle(Context c, String text) {
        final TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ACCENT);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    private ProgressBar meter(Context c) {
        final ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setProgress(0);
        return p;
    }

    private interface OnValue { void onValue(float v); }

    private void addSlider(Context c, LinearLayout parent, String label, float min, float max,
                           float value, String unit, OnValue cb) {
        final LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(0, dp(6), 0, dp(6));

        final TextView tv = new TextView(c);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setText(label + ": " + fmt(value) + unit);
        holder.addView(tv);

        final SeekBar bar = new SeekBar(c);
        final int steps = 240;
        bar.setMax(steps);
        bar.setProgress((int) ((value - min) / (max - min) * steps));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final float v = min + (max - min) * progress / (float) steps;
                tv.setText(label + ": " + fmt(v) + unit);
                cb.onValue(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        holder.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(holder);
    }

    private String fmt(float v) {
        return (v > 0 ? "+" : "") + String.format("%.1f", v);
    }

    private void Toast(Context c, String msg, int length) {
        android.widget.Toast.makeText(c, msg, length).show();
    }

    private void Toast(Context c, String msg) {
        android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
