package org.telegram.messenger.gramify;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Search results ki list. Poora row programmatically bana hai (koi layout XML nahi)
 * taaki ye har Telegram version me compile ho jaye.
 */
public class SongAdapter extends BaseAdapter {

    private final Context context;
    private final ArrayList<Song> items = new ArrayList<>();
    private String playingId;
    private int textColor, grayColor, dividerColor, highlightColor;

    public SongAdapter(Context context) {
        this.context = context;
        try {
            textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
            dividerColor = Theme.getColor(Theme.key_divider);
        } catch (Throwable t) {
            textColor = Color.BLACK;
            grayColor = 0xFF888888;
            dividerColor = 0x22000000;
        }
        highlightColor = 0x224A8CFF;
    }

    public void setItems(ArrayList<Song> songs) {
        items.clear();
        if (songs != null) items.addAll(songs);
        notifyDataSetChanged();
    }

    public void setPlayingId(String id) {
        playingId = id;
        notifyDataSetChanged();
    }

    public ArrayList<Song> getItems() {
        return items;
    }

    @Override
    public int getCount() { return items.size(); }

    @Override
    public Object getItem(int position) { return items.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row;
        TextView title, sub;
        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            title = (TextView) row.getChildAt(0);
            sub = (TextView) row.getChildAt(1);
        } else {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);

            sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);

            row.addView(title);
            row.addView(sub);
            row.setTag(title);
        }

        final Song s = items.get(position);
        title.setTextColor(playingId != null && playingId.equals(s.id) ? 0xFF4A8CFF : textColor);
        sub.setTextColor(playingId != null && playingId.equals(s.id) ? 0xFF4A8CFF : grayColor);

        title.setText(s.title == null ? "" : s.title);
        final StringBuilder sb = new StringBuilder();
        if (s.subtitle() != null && !s.subtitle().isEmpty()) sb.append(s.subtitle());
        if (s.durationSec > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s.durationText());
        }
        if (s.bitrate > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s.bitrate).append(" kbps");
        }
        sub.setText(sb.toString());

        row.setBackgroundColor(playingId != null && playingId.equals(s.id) ? highlightColor : Color.TRANSPARENT);
        final View divider = new View(context);
        divider.setBackgroundColor(dividerColor);
        // divider ko row ke andar sabse neeche 1px
        if (row.getChildCount() == 2) {
            row.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            ((LinearLayout.LayoutParams) divider.getLayoutParams()).topMargin = AndroidUtilities.dp(6);
        }
        return row;
    }
}
