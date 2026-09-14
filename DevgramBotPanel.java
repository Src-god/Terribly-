package org.telegram.messenger.gramify;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * DevgramBotPanel — 10 slots, parallel Name + Message, flood auto-switch.
 * 5-5 bot group split + emoji rotation + fancy font.
 */
public class DevgramBotPanel extends LinearLayout {

    // =====================================================
    // EMOJI POOL (Python DEFAULT_NC_EMOJIS se)
    // =====================================================
    private static final String[] EMOJI_POOL = {
            "𓍼ོ↻", "˚⊱🪷⊰˚", "⛧⃝", "💘", "💝", "💖", "💗", "💓", "💞", "💕",
            "💟", "❣️", "❤️‍🔥", "❤️", "🩷", "🧡", "💛", "💚", "💙", "🩵",
            "💜", "🤎", "🖤", "🩶", "🤍", "💢", "🫯", "💤", "🫶🏻", "👀",
            "🏄‍♂️", "🤺", "🦄", "🦇", "🦅", "🕊", "🦚", "🦜", "🐦‍🔥", "🐊",
            "🐍", "🐉", "🦈", "🪼", "🪸", "🕸", "🕷", "🍃", "🍂", "🍁",
            "🛘", "🗻", "🌋", "🗽", "🗼", "🎡", "🛑", "🚨", "⚓️", "🛟",
            "⏱️", "🕛", "🕧", "🕐", "🕜", "🕑", "🕝"
    };

    // =====================================================
    // FANCY FONT MAP (Python to_fancy se)
    // =====================================================
    private static final Map<Character, String> FANCY = new HashMap<>();
    static {
        FANCY.put('a', "ᴀ"); FANCY.put('b', "ʙ"); FANCY.put('c', "ᴄ"); FANCY.put('d', "ᴅ");
        FANCY.put('e', "ᴇ"); FANCY.put('f', "ғ"); FANCY.put('g', "ɢ"); FANCY.put('h', "ʜ");
        FANCY.put('i', "ɪ"); FANCY.put('j', "ᴊ"); FANCY.put('k', "ᴋ"); FANCY.put('l', "ʟ");
        FANCY.put('m', "ᴍ"); FANCY.put('n', "ɴ"); FANCY.put('o', "ᴏ"); FANCY.put('p', "ᴘ");
        FANCY.put('q', "ǫ"); FANCY.put('r', "ʀ"); FANCY.put('s', "s"); FANCY.put('t', "ᴛ");
        FANCY.put('u', "ᴜ"); FANCY.put('v', "ᴠ"); FANCY.put('w', "ᴡ"); FANCY.put('x', "x");
        FANCY.put('y', "ʏ"); FANCY.put('z', "ᴢ");
        FANCY.put('A', "𝐀"); FANCY.put('B', "𝐁"); FANCY.put('C', "𝐂"); FANCY.put('D', "𝐃");
        FANCY.put('E', "𝐄"); FANCY.put('F', "𝐅"); FANCY.put('G', "𝐆"); FANCY.put('H', "𝐇");
        FANCY.put('I', "𝐈"); FANCY.put('J', "𝐉"); FANCY.put('K', "𝐊"); FANCY.put('L', "𝐋");
        FANCY.put('M', "𝐌"); FANCY.put('N', "𝐍"); FANCY.put('O', "𝐎"); FANCY.put('P', "𝐏");
        FANCY.put('Q', "𝐐"); FANCY.put('R', "𝐑"); FANCY.put('S', "𝐒"); FANCY.put('T', "𝐓");
        FANCY.put('U', "𝐔"); FANCY.put('V', "𝐕"); FANCY.put('W', "𝐖"); FANCY.put('X', "𝐗");
        FANCY.put('Y', "𝐘"); FANCY.put('Z', "𝐙");
        FANCY.put('0', "𝟎"); FANCY.put('1', "𝟏"); FANCY.put('2', "𝟐"); FANCY.put('3', "𝟑");
        FANCY.put('4', "𝟒"); FANCY.put('5', "𝟓"); FANCY.put('6', "𝟔"); FANCY.put('7', "𝟕");
        FANCY.put('8', "𝟖"); FANCY.put('9', "𝟗");
    }

    private static String toFancy(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            String f = FANCY.get(c);
            sb.append(f != null ? f : c);
        }
        return sb.toString();
    }

    // =====================================================
    // FLOOD ENGINE — 5-5 GROUP SPLIT
    // =====================================================
    private static final List<String> group1 = new ArrayList<>();
    private static final List<String> group2 = new ArrayList<>();
    private static volatile int activeGroup = 0;
    private static volatile boolean rateLimitHit = false;
    private static volatile double backoff = 1.0;
    private static final Object lock = new Object();

    // =====================================================
    // TASK MANAGEMENT
    // =====================================================
    private final Map<String, AtomicBoolean> nameTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> msgTasks = new ConcurrentHashMap<>();
    private ExecutorService nameExecutor, msgExecutor;
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // =====================================================
    // UI REFS
    // =====================================================
    private EditText groupLinkEt;
    private EditText nameThreadsEt, nameDelayEt, nameTextEt;
    private Spinner namePositionSpinner;
    private CheckBox nameFancyCb;
    private EditText msgThreadsEt, msgDelayEt, msgTextEt;
    private CheckBox msgFancyCb;
    private TextView nameStatus, msgStatus;

    private final Runnable onClose;
    private final SharedPreferences pref;

    public DevgramBotPanel(Context context, Runnable onClose) {
        super(context);
        this.onClose = onClose;
        this.pref = context.getSharedPreferences("devgram_panel_pref", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setBackground(bg(0xEE000000, dp(16)));
        setPadding(dp(12), dp(12), dp(12), dp(12));
        buildUI(context);
    }

    // =====================================================
    // UI BUILD
    // =====================================================
    private void buildUI(Context c) {
        ScrollView scroll = new ScrollView(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(VERTICAL);

        TextView title = new TextView(c);
        title.setText("Devgram Bot Manager");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(c);
        sub.setText("Developed By Dev  |  Max 10 Bots  |  Flood Auto-Switch");
        sub.setTextColor(0xFFAAAAAA);
        sub.setTextSize(11);
        sub.setPadding(0, 0, 0, dp(8));
        root.addView(sub);

        groupLinkEt = edit(c, "Group Link / Chat ID (-100...)");
        groupLinkEt.setText(pref.getString("group_link", ""));
        root.addView(groupLinkEt);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        LinearLayout nameSection = buildSection(c, true);
        LinearLayout msgSection = buildSection(c, false);

        LayoutParams half = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(6);
        nameSection.setLayoutParams(half);

        LayoutParams half2 = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(6);
        msgSection.setLayoutParams(half2);

        row.addView(nameSection);
        row.addView(msgSection);
        root.addView(row);

        scroll.addView(root);
        addView(scroll);
    }

    private LinearLayout buildSection(Context c, boolean isName) {
        LinearLayout sec = new LinearLayout(c);
        sec.setOrientation(VERTICAL);
        sec.setPadding(dp(8), dp(8), dp(8), dp(8));
        sec.setBackground(bg(0xFF1A1A1A, dp(12)));

        TextView header = new TextView(c);
        header.setText(isName ? "NAME CHANGE" : "MESSAGE SEND");
        header.setTextColor(Color.WHITE);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextSize(13);
        sec.addView(header);

        sec.addView(label(c, "Threads (1-10)"));
        EditText threadsEt = edit(c, "ex: 5");
        threadsEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        sec.addView(threadsEt);

        sec.addView(label(c, isName ? "Text (emoji rotate)" : "Text (comma, separated)"));
        EditText textEt = edit(c, isName ? "ex: Dev" : "Hello,Hi,Bye");
        sec.addView(textEt);

        Spinner posSpin = null;
        if (isName) {
            sec.addView(label(c, "Emoji Position"));
            posSpin = new Spinner(c);
            ArrayAdapter<String> ad = new ArrayAdapter<>(c,
                    android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"both", "prefix", "suffix"});
            posSpin.setAdapter(ad);
            sec.addView(posSpin);
        }

        sec.addView(label(c, "Delay (sec) - 0 allowed"));
        EditText delayEt = edit(c, "ex: 0");
        delayEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sec.addView(delayEt);

        CheckBox fancy = new CheckBox(c);
        fancy.setText("Fancy Font");
        fancy.setTextColor(Color.WHITE);
        fancy.setChecked(true);
        sec.addView(fancy);

        LinearLayout btnRow = new LinearLayout(c);
        btnRow.setOrientation(HORIZONTAL);
        Button startB = whiteBtn(c, "START");
        Button stopB = blackBtn(c, "STOP");
        LayoutParams bp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        startB.setLayoutParams(bp);
        stopB.setLayoutParams(bp);
        btnRow.addView(startB);
        btnRow.addView(stopB);
        sec.addView(btnRow);

        TextView status = new TextView(c);
        status.setTextColor(0xFFFFAA00);
        status.setTextSize(10);
        status.setPadding(0, dp(6), 0, 0);
        sec.addView(status);

        if (isName) {
            nameThreadsEt = threadsEt; nameTextEt = textEt; nameDelayEt = delayEt;
            namePositionSpinner = posSpin; nameFancyCb = fancy;
            nameStatus = status;
            startB.setOnClickListener(v -> startNameChange());
            stopB.setOnClickListener(v -> stopNameChange());
        } else {
            msgThreadsEt = threadsEt; msgTextEt = textEt; msgDelayEt = delayEt;
            msgFancyCb = fancy;
            msgStatus = status;
            startB.setOnClickListener(v -> startMessageSend());
            stopB.setOnClickListener(v -> stopMessageSend());
        }
        return sec;
    }

    // =====================================================
    // NAME CHANGE
    // =====================================================
    private void startNameChange() {
        String groupLink = groupLinkEt.getText().toString().trim();
        String text = nameTextEt.getText().toString().trim();
        if (groupLink.isEmpty() || text.isEmpty()) {
            nameStatus.setText("Group link + text required");
            return;
        }
        pref.edit().putString("group_link", groupLink).apply();

        int threads = parseInt(nameThreadsEt.getText().toString(), 5, 1, 10);
        double delay = parseDouble(nameDelayEt.getText().toString(), 0.0);
        String position = (String) namePositionSpinner.getSelectedItem();
        boolean fancy = nameFancyCb.isChecked();

        List<String> tokens = getAllTokens();
        if (tokens.isEmpty()) {
            nameStatus.setText("Pehle settings me tokens save karo");
            return;
        }

        rebuildGroups(tokens);
        stopNameChange();

        String finalText = fancy ? toFancy(text) : text;
        String chatId = extractChatId(groupLink);
        if (chatId == null) {
            nameStatus.setText("Invalid group link");
            return;
        }

        nameExecutor = Executors.newFixedThreadPool(threads);
        int botCount = Math.min(threads, tokens.size());
        nameStatus.setText("Name change running (" + botCount + " bots)...");

        for (int i = 0; i < botCount; i++) {
            String token = tokens.get(i % tokens.size());
            String taskId = "nc_" + i + "_" + System.currentTimeMillis();
            AtomicBoolean flag = new AtomicBoolean(true);
            nameTasks.put(taskId, flag);
            final double fDelay = delay;
            final String fPos = position;
            nameExecutor.execute(() -> ncLoop(token, chatId, finalText, fPos, fDelay, flag));
        }
    }

    private void stopNameChange() {
        for (AtomicBoolean f : nameTasks.values()) f.set(false);
        nameTasks.clear();
        if (nameExecutor != null) { nameExecutor.shutdownNow(); nameExecutor = null; }
        if (nameStatus != null) nameStatus.setText("Stopped");
    }

    private void ncLoop(String token, String chatId, String target, String position,
                        double delaySec, AtomicBoolean running) {
        String lastEmoji = null;
        long delayMs = (long) (delaySec * 1000);
        Random rnd = new Random();
        while (running.get()) {
            if (rateLimitHit) {
                if (getGroup(token) != activeGroup) {
                    sleep(50);
                    continue;
                }
            }
            String emoji;
            do { emoji = EMOJI_POOL[rnd.nextInt(EMOJI_POOL.length)]; }
            while (emoji.equals(lastEmoji) && EMOJI_POOL.length > 1);
            lastEmoji = emoji;

            String msg;
            if ("prefix".equals(position)) msg = emoji + " " + target;
            else if ("suffix".equals(position)) msg = target + " " + emoji;
            else msg = emoji + " " + target + " " + emoji;
            if (msg.length() > 255) msg = msg.substring(0, 255);

            callSetChatTitle(token, chatId, msg);
            sleep(delayMs);
        }
    }

    // =====================================================
    // MESSAGE SEND
    // =====================================================
    private void startMessageSend() {
        String groupLink = groupLinkEt.getText().toString().trim();
        String text = msgTextEt.getText().toString().trim();
        if (groupLink.isEmpty() || text.isEmpty()) {
            msgStatus.setText("Group link + text required");
            return;
        }
        pref.edit().putString("group_link", groupLink).apply();

        int threads = parseInt(msgThreadsEt.getText().toString(), 5, 1, 10);
        double delay = parseDouble(msgDelayEt.getText().toString(), 0.0);
        boolean fancy = msgFancyCb.isChecked();

        List<String> tokens = getAllTokens();
        if (tokens.isEmpty()) {
            msgStatus.setText("Pehle settings me tokens save karo");
            return;
        }

        rebuildGroups(tokens);
        stopMessageSend();

        String[] parts = text.split(",");
        List<String> pool = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) pool.add(fancy ? toFancy(t) : t);
        }
        if (pool.isEmpty()) {
            msgStatus.setText("Text khali hai");
            return;
        }

        String chatId = extractChatId(groupLink);
        if (chatId == null) {
            msgStatus.setText("Invalid group link");
            return;
        }

        msgExecutor = Executors.newFixedThreadPool(threads);
        int botCount = Math.min(threads, tokens.size());
        msgStatus.setText("Message sending (" + botCount + " bots)...");

        for (int i = 0; i < botCount; i++) {
            String token = tokens.get(i % tokens.size());
            String taskId = "msg_" + i + "_" + System.currentTimeMillis();
            AtomicBoolean flag = new AtomicBoolean(true);
            msgTasks.put(taskId, flag);
            final double fDelay = delay;
            final List<String> fPool = pool;
            msgExecutor.execute(() -> msgLoop(token, chatId, fPool, fDelay, flag));
        }
    }

    private void stopMessageSend() {
        for (AtomicBoolean f : msgTasks.values()) f.set(false);
        msgTasks.clear();
        if (msgExecutor != null) { msgExecutor.shutdownNow(); msgExecutor = null; }
        if (msgStatus != null) msgStatus.setText("Stopped");
    }

    private void msgLoop(String token, String chatId, List<String> pool,
                         double delaySec, AtomicBoolean running) {
        int i = 0;
        long delayMs = (long) (delaySec * 1000);
        while (running.get()) {
            if (rateLimitHit) {
                if (getGroup(token) != activeGroup) { sleep(50); continue; }
            }
            String msg = pool.get(i % pool.size());
            callSendMessage(token, chatId, msg);
            i++;
            sleep(delayMs);
        }
    }

    // =====================================================
    // NETWORK CALLS
    // =====================================================
    private void callSetChatTitle(String token, String chatId, String title) {
        networkExecutor.execute(() -> {
            try {
                String params = "chat_id=" + encode(chatId) + "&title=" + encode(title);
                handleResponse(telegramRequest(token, "setChatTitle", params, false));
            } catch (Exception ignore) {
            }
        });
    }

    private void callSendMessage(String token, String chatId, String text) {
        networkExecutor.execute(() -> {
            try {
                String params = "chat_id=" + encode(chatId) + "&text=" + encode(text);
                handleResponse(telegramRequest(token, "sendMessage", params, true));
            } catch (Exception ignore) {
            }
        });
    }

    private String telegramRequest(String token, String method, String params, boolean post) throws IOException {
        URL endpoint = new URL("https://api.telegram.org/bot" + token + "/" + method
                + (post ? "" : "?" + params));
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(post ? "POST" : "GET");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (post) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(params.getBytes("UTF-8"));
            }
        }
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        } finally {
            connection.disconnect();
        }
        return result.toString();
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignore) {
            return value;
        }
    }

    // =====================================================
    // FLOOD DETECTION + AUTO GROUP SWITCH
    // =====================================================
    private void handleResponse(String body) {
        String lower = body.toLowerCase();
        if (lower.contains("retry_after") || lower.contains("too many requests")
                || lower.contains("flood")) {
            synchronized (lock) {
                if (!rateLimitHit) {
                    activeGroup = 1 - activeGroup;
                    backoff = Math.min(backoff * 2, 10.0);
                    rateLimitHit = true;
                    final int ag = activeGroup;
                    final double bo = backoff;
                    ui.post(() -> {
                        if (nameStatus != null)
                            nameStatus.setText("Flood -> Group " + (ag + 1) + " wait " + bo + "s");
                        if (msgStatus != null)
                            msgStatus.setText("Flood -> Group " + (ag + 1) + " wait " + bo + "s");
                    });
                    sleep((long) (backoff * 1000));
                }
            }
        } else if (body.contains("\"ok\":true")) {
            if (rateLimitHit) {
                synchronized (lock) {
                    rateLimitHit = false;
                    backoff = 1.0;
                }
            }
        }
    }

    // =====================================================
    // TOKENS + 5-5 GROUP SPLIT
    // =====================================================
    private List<String> getAllTokens() {
        SharedPreferences s = getContext().getSharedPreferences(
                "devgram_bot_10_pref", Context.MODE_PRIVATE);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String t = s.getString("token_" + i, "").trim();
            if (!t.isEmpty() && t.contains(":")) list.add(t);
        }
        return list;
    }

    /** 5-5 group split — pehle aadhe Group1 me, baaki Group2 me */
    private void rebuildGroups(List<String> tokens) {
        group1.clear();
        group2.clear();
        int half = tokens.size() / 2;
        for (int i = 0; i < tokens.size(); i++) {
            if (i < half) group1.add(tokens.get(i));
            else group2.add(tokens.get(i));
        }
    }

    private int getGroup(String token) {
        if (group1.contains(token)) return 0;
        if (group2.contains(token)) return 1;
        return 0;
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private String extractChatId(String link) {
        link = link.trim();
        if (link.matches("-?\\d+")) return link;
        if (link.startsWith("https://t.me/")) {
            String name = link.substring("https://t.me/".length());
            if (name.startsWith("+")) return null;
            return "@" + name;
        }
        if (link.startsWith("t.me/")) {
            String name = link.substring(5);
            if (name.startsWith("+")) return null;
            return "@" + name;
        }
        if (link.startsWith("@")) return link;
        return null;
    }

    private int parseInt(String s, int def, int min, int max) {
        try { int v = Integer.parseInt(s.trim()); return Math.max(min, Math.min(max, v)); }
        catch (Exception e) { return def; }
    }

    private double parseDouble(String s, double def) {
        try { double v = Double.parseDouble(s.trim()); return Math.max(0, v); }
        catch (Exception e) { return def; }
    }

    private void sleep(long ms) {
        if (ms <= 0) { Thread.yield(); return; }
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    // =====================================================
    // UI HELPERS
    // =====================================================
    private EditText edit(Context c, String hint) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setHintTextColor(0xFF888888);
        et.setTextColor(Color.WHITE);
        et.setTextSize(12);
        et.setBackground(bg(0xFF222222, dp(8)));
        et.setPadding(dp(8), dp(8), dp(8), dp(8));
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        et.setLayoutParams(lp);
        return et;
    }

    private TextView label(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(10);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    private Button whiteBtn(Context c, String t) {
        Button b = new Button(c);
        b.setText(t); b.setTextColor(Color.BLACK); b.setAllCaps(false);
        b.setBackground(bg(Color.WHITE, dp(16)));
        return b;
    }

    private Button blackBtn(Context c, String t) {
        Button b = new Button(c);
        b.setText(t); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackground(bg(0xFF333333, dp(16)));
        return b;
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}