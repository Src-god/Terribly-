package org.telegram.messenger.gramify;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * DevgramBotFragment — Settings -> "Devgram Bot"
 * 10 token slots + ON button (starts bubble service).
 */
public class DevgramBotFragment extends BaseFragment {

    private static final String PREF = "devgram_bot_10_pref";
    private static final int MAX_BOTS = 10;

    private final EditText[] tokenInputs = new EditText[MAX_BOTS];
    private TextView status;

    @Override
    public View createView(Context context) {
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        ScrollView scroll = new ScrollView(context);
        LinearLayout c = new LinearLayout(context);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(context);
        title.setText("Devgram Bot Manager");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        c.addView(title);

        TextView sub = new TextView(context);
        sub.setText("Developed By Dev | 10 Slots | Flood Auto-Switch");
        sub.setTextColor(0xFFAAAAAA);
        sub.setTextSize(11);
        sub.setPadding(0, 0, 0, dp(12));
        c.addView(sub);

        TextView info = new TextView(context);
        info.setText("Bot tokens daalo (1 se 10). Bot @BotFather se banao aur group me admin banao.");
        info.setTextColor(0xFF888888);
        info.setTextSize(11);
        info.setPadding(0, 0, 0, dp(12));
        c.addView(info);

        for (int i = 0; i < MAX_BOTS; i++) {
            tokenInputs[i] = edit(context, "Bot Token " + (i + 1) + " - ex: 123456:ABC...");
            tokenInputs[i].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            tokenInputs[i].setText(pref.getString("token_" + i, ""));
            c.addView(tokenInputs[i]);
        }

        Button saveBtn = whiteBtn(context, "Save All Tokens");
        c.addView(saveBtn);

        Button onBtn = whiteBtn(context, "ON - Start Devgram Bot Bubble");
        c.addView(onBtn);

        Button offBtn = blackBtn(context, "OFF - Stop Bubble");
        c.addView(offBtn);

        status = new TextView(context);
        status.setTextColor(Color.WHITE);
        status.setPadding(0, dp(16), 0, 0);
        status.setTextSize(12);
        c.addView(status);

        saveBtn.setOnClickListener(v -> {
            SharedPreferences.Editor ed = pref.edit();
            int saved = 0;
            for (int i = 0; i < MAX_BOTS; i++) {
                String t = tokenInputs[i].getText().toString().trim();
                if (!t.isEmpty() && t.contains(":")) {
                    ed.putString("token_" + i, t);
                    saved++;
                }
            }
            ed.apply();
            status.setText(saved + " tokens saved.");
        });

        onBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(context)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
                context.startActivity(i);
                status.setText("Overlay permission do, phir ON dabao");
                return;
            }
            Intent svc = new Intent(context, DevgramBubbleService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                status.setText("Bubble ON - screen pe 🫍 dikhega");
            } catch (Throwable t) {
                status.setText("Start fail: " + t.getMessage());
            }
        });

        offBtn.setOnClickListener(v -> {
            Intent svc = new Intent(context, DevgramBubbleService.class);
            try { context.stopService(svc); status.setText("Bubble OFF"); }
            catch (Throwable t) { status.setText("Stop fail: " + t.getMessage()); }
        });

        scroll.addView(c);
        root.addView(scroll);
        return root;
    }

    private EditText edit(Context ctx, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(0xFF888888);
        et.setTextColor(Color.WHITE);
        et.setBackground(bg(0xFF1A1A1A, dp(12)));
        et.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    private Button whiteBtn(Context ctx, String t) {
        Button b = new Button(ctx);
        b.setText(t); b.setTextColor(Color.BLACK); b.setAllCaps(false);
        b.setBackground(bg(Color.WHITE, dp(20)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button blackBtn(Context ctx, String t) {
        Button b = new Button(ctx);
        b.setText(t); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackground(bg(0xFF333333, dp(20)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) { return AndroidUtilities.dp(v); }
}
