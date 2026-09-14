package org.telegram.messenger.gramify;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Gramify Music — in-built music search (JioSaavn) + player.
 *
 * Telegram ke andar ek normal fragment hai: Settings > Gramify Music se khulta hai.
 * Poora UI programmatically bana hai (koi layout XML nahi) — isse kisi bhi
 * Telegram version ke sath compile ho jata hai.
 */
public class GramifyMusicFragment extends BaseFragment {

    private static final int ACCENT = 0xFF4A8CFF;

    private EditText searchField;
    private ListView listView;
    private SongAdapter adapter;
    private ProgressBar loading;
    private TextView statusView;
    private TextView nowPlaying;
    private Button playButton;
    private Button eqButton;
    private int bgColor, textColor, grayColor;

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle("Gramify Music");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    }
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

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        if (actionBar != null) {
            root.addView(actionBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ActionBar.getCurrentActionBarHeight()));
        }

        // ---------- search row ----------
        final LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(10), dp(8), dp(10), dp(8));

        searchField = new EditText(context);
        searchField.setHint("Song, artist ya movie...");
        searchField.setTextColor(textColor);
        searchField.setHintTextColor(grayColor);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button searchButton = new Button(context);
        searchButton.setText("Search");
        searchButton.setAllCaps(false);
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- status + loading ----------
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setTextColor(grayColor);
        statusView.setPadding(dp(16), 0, dp(16), dp(6));
        statusView.setText("JioSaavn se gaane search karo — 96/160/320 kbps");
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        loading = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true);
        loading.setVisibility(View.GONE);
        root.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // ---------- results ----------
        final FrameLayout listHolder = new FrameLayout(context);
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        adapter = new SongAdapter(context);
        listView.setAdapter(adapter);
        listHolder.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- player bar ----------
        final LinearLayout playerBar = new LinearLayout(context);
        playerBar.setOrientation(LinearLayout.HORIZONTAL);
        playerBar.setGravity(Gravity.CENTER_VERTICAL);
        playerBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        playerBar.setBackgroundColor(0x114A8CFF);

        nowPlaying = new TextView(context);
        nowPlaying.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nowPlaying.setTextColor(textColor);
        nowPlaying.setTypeface(Typeface.DEFAULT_BOLD);
        nowPlaying.setSingleLine(true);
        nowPlaying.setText("Kuch play nahi ho raha");
        playerBar.addView(nowPlaying, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        eqButton = new Button(context);
        eqButton.setText("EQ: Flat");
        eqButton.setAllCaps(false);
        eqButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        playerBar.addView(eqButton);

        playButton = new Button(context);
        playButton.setText("▶");
        playButton.setAllCaps(false);
        playerBar.addView(playButton);

        root.addView(playerBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- wiring ----------
        final Runnable doSearch = () -> {
            final String q = searchField.getText().toString().trim();
            if (q.isEmpty()) {
                Toast.makeText(context, "Kuch likho pehle 🙂", Toast.LENGTH_SHORT).show();
                return;
            }
            loading.setVisibility(View.VISIBLE);
            statusView.setText("Searching...");
            listView.setVisibility(View.VISIBLE);
            SaavnApi.search(q, 30, (result, error) -> AndroidUtilities.runOnUIThread(() -> {
                loading.setVisibility(View.GONE);
                if (error != null || result == null) {
                    statusView.setText("Error: " + error);
                    return;
                }
                adapter.setItems(result);
                statusView.setText(result.size() + " results — kisi gaane pe tap karo");
            }));
        };

        searchButton.setOnClickListener(v -> doSearch.run());
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch.run();
                return true;
            }
            return false;
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            final Song song = (Song) adapter.getItem(position);
            statusView.setText("URL resolve ho raha hai: " + song.title + " ...");
            SaavnApi.resolveStreamUrl(song, 320, (s, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null || s == null || s.streamUrl == null) {
                    statusView.setText("Nahi chala: " + error);
                    return;
                }
                adapter.setPlayingId(s.id);
                GramifyPlayer.get().play(context, s, s.streamUrl);
                nowPlaying.setText(s.title + " — " + (s.bitrate > 0 ? s.bitrate + " kbps" : ""));
                playButton.setText("⏸");
            }));
        });

        playButton.setOnClickListener(v -> {
            GramifyPlayer.get().toggle();
            playButton.setText(GramifyPlayer.get().isPlaying() ? "⏸" : "▶");
        });

        eqButton.setOnClickListener(v -> {
            final GramifyPlayer p = GramifyPlayer.get();
            final int next = (p.getEqPreset() + 1) % GramifyPlayer.EQ_NAME.length;
            p.applyEqPreset(next);
            eqButton.setText("EQ: " + GramifyPlayer.EQ_NAME[next]);
        });

        GramifyPlayer.get().setListener((playing, song, error) -> AndroidUtilities.runOnUIThread(() -> {
            playButton.setText(playing ? "⏸" : "▶");
            if (error != null) statusView.setText("Player: " + error);
        }));

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        GramifyPlayer.get().setListener(null);
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
