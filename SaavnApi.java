package org.telegram.messenger.gramify;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gramify music search — JioSaavn public web API.
 *
 * <h3>Kaise kaam karta hai</h3>
 * <ol>
 *   <li><b>Search</b>: {@code api.php?__call=search.getResults&q=...&api_version=4&ctx=web6dot0}
 *       → total, results[] (id, title, subtitle, image, more_info...)</li>
 *   <li><b>Stream URL</b>: {@code api.php?__call=song.getDetails&pids=<songId>}
 *       → more_info.encrypted_media_url</li>
 *   <li><b>Decrypt</b>: DES/ECB/PKCS5 with key {@code 38346591}
 *       → {@code https://aac.saavncdn.com/.../xxx_96.mp4}</li>
 *   <li><b>Quality</b>: {@code _96.mp4} ko {@code _320.mp4} se replace (agar 320kbps available ho)</li>
 * </ol>
 *
 * Ye class PURE JAVA hai (koi Android import nahi) — isliye ise desktop par bhi
 * test kiya ja sakta hai: {@code gramify/test/SaavnSmokeTest.java}
 *
 * NOTE (kyunki ye important hai): JioSaavn ki ye API official/public nahi hai;
 * ye unki website use karti hai. Personal/educational use ke liye theek hai, aur
 * aapko apni app me ye clearly likhna chahiye. Play Store par aisi app daalna
 * content-licensing issue ban sakta hai.
 */
public final class SaavnApi {

    private static final String API = "https://www.jiosaavn.com/api.php";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Gramify/1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    /** JioSaavn ka public DES key (sabhi clients yahi use karte hain). */
    private static final byte[] DES_KEY = "38346591".getBytes();

    public interface Callback<T> {
        void onResult(T result, String error);
    }

    /** UI ko batane ke liye ki song mila ya nahi. */
    public interface UrlCallback {
        void onUrl(String url, String error);
    }

    private SaavnApi() {}

    /* ------------------------------------------------------------------ *
     *  Search  (background thread par chalta hai, callback main thread pe)
     * ------------------------------------------------------------------ */

    public static void search(final String query, final int limit, final Callback<ArrayList<Song>> cb) {
        new Thread(() -> {
            try {
                final String url = API
                        + "?__call=search.getResults"
                        + "&q=" + URLEncoder.encode(query == null ? "" : query, "UTF-8")
                        + "&_format=json&_marker=0&api_version=4&ctx=web6dot0"
                        + "&n=" + Math.max(1, Math.min(limit, 50)) + "&p=1";
                final String body = httpGet(url);
                final ArrayList<Song> songs = parseSearch(body);
                cb.onResult(songs, songs.isEmpty() ? "No results" : null);
            } catch (Throwable t) {
                cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "gramify-search").start();
    }

    /** Blocking version (test / worker thread). */
    public static ArrayList<Song> searchBlocking(String query, int limit) throws Exception {
        final String url = API
                + "?__call=search.getResults"
                + "&q=" + URLEncoder.encode(query == null ? "" : query, "UTF-8")
                + "&_format=json&_marker=0&api_version=4&ctx=web6dot0"
                + "&n=" + Math.max(1, Math.min(limit, 50)) + "&p=1";
        return parseSearch(httpGet(url));
    }

    /**
     * Stream URL resolve karta hai (search result me usually nahi hota).
     * Callback main thread pe aa jata hai (Android par) — desktop par same thread.
     */
    public static void resolveStreamUrl(final Song song, final int preferredBitrate, final Callback<Song> cb) {
        new Thread(() -> {
            try {
                final String url = fetchStreamUrlBlocking(song.id, preferredBitrate);
                if (url == null) {
                    cb.onResult(null, "Stream URL nahi mila");
                } else {
                    song.streamUrl = url;
                    song.bitrate = detectBitrate(url);
                    cb.onResult(song, null);
                }
            } catch (Throwable t) {
                cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "gramify-url").start();
    }

    public static String fetchStreamUrlBlocking(String songId, int preferredBitrate) throws Exception {
        final String url = API + "?__call=song.getDetails"
                + "&pids=" + URLEncoder.encode(songId, "UTF-8")
                + "&api_version=4&ctx=web6dot0&_format=json&_marker=0";
        final Map<String, Object> root = Json.parseObject(httpGet(url));
        final Song s = parseDetails(root);
        if (s == null || s.streamUrl == null) return null;
        return withBitrate(s.streamUrl, preferredBitrate, is320(root));
    }

    /* ------------------------------------------------------------------ *
     *  Parsing
     * ------------------------------------------------------------------ */

    public static ArrayList<Song> parseSearch(String json) {
        final ArrayList<Song> out = new ArrayList<>();
        if (json == null) return out;
        final Map<String, Object> root = Json.parseObject(json);
        final List<Object> results = Json.arr(root, "results");
        if (results == null) return out;
        for (Object o : results) {
            if (!(o instanceof Map)) continue;
            //noinspection unchecked
            final Map<String, Object> m = (Map<String, Object>) o;
            final Song s = parseSongMap(m);
            if (s != null && s.id != null) out.add(s);
        }
        return out;
    }

    /** song.getDetails ka response: {"songs":[{...}]} (kabhi kabhi id-keyed object). */
    public static Song parseDetails(Map<String, Object> root) {
        if (root == null) return null;
        final List<Object> songs = Json.arr(root, "songs");
        if (songs != null && !songs.isEmpty()) {
            final Object first = songs.get(0);
            if (first instanceof Map) {
                //noinspection unchecked
                return parseSongMap((Map<String, Object>) first);
            }
        }
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getValue() instanceof Map) {
                //noinspection unchecked
                final Song s = parseSongMap((Map<String, Object>) e.getValue());
                if (s != null && s.streamUrl != null) return s;
            }
        }
        return null;
    }

    private static Song parseSongMap(Map<String, Object> m) {
        if (m == null) return null;
        final Song s = new Song();
        s.id = Json.str(m, "id");
        s.title = clean(Json.str(m, "title", Json.str(m, "song")));
        s.artist = clean(Json.str(m, "subtitle"));
        s.language = Json.str(m, "language");
        s.year = Json.str(m, "year");
        s.permaUrl = Json.str(m, "perma_url");
        s.image = upgradeImage(Json.str(m, "image"));

        final Map<String, Object> mi = Json.obj(m, "more_info");
        if (mi != null) {
            s.album = clean(Json.str(mi, "album"));
            s.durationSec = (int) Json.num(mi, "duration", 0);
            if (s.artist == null || s.artist.isEmpty()) s.artist = clean(Json.str(mi, "music"));
            final String enc = Json.str(mi, "encrypted_media_url");
            if (enc != null && !enc.isEmpty()) {
                final String dec = decryptMediaUrl(enc);
                if (dec != null) {
                    s.streamUrl = withBitrate(dec, 320, Json.bool(mi, "320kbps", false));
                }
            }
        }
        if (s.durationSec == 0) s.durationSec = (int) Json.num(m, "duration", 0);
        return s;
    }

    /* ------------------------------------------------------------------ *
     *  DES decrypt + quality
     * ------------------------------------------------------------------ */

    /** JioSaavn media URL decrypt (DES/ECB/PKCS5, key 38346591). */
    public static String decryptMediaUrl(String encryptedBase64) {
        try {
            final byte[] data = base64Decode(encryptedBase64);
            final Cipher c = Cipher.getInstance("DES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(DES_KEY, "DES"));
            final byte[] out = c.doFinal(data);
            final String url = new String(out, "UTF-8").trim();
            return url.startsWith("http") ? url : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** _96.mp4 -> _320.mp4 (agar track 320kbps me available ho). */
    public static String withBitrate(String url, int preferredBitrate, boolean has320) {
        if (url == null) return null;
        int br = preferredBitrate;
        if (br <= 0) br = 320;
        if (br > 160 && !has320) br = 96;
        final String quality = (br >= 320 ? "320" : br >= 160 ? "160" : "96");
        return url.replaceAll("_(12|48|96|160|320)\\.mp4", "_" + quality + ".mp4");
    }

    public static int detectBitrate(String url) {
        if (url == null) return 0;
        if (url.contains("_320.mp4")) return 320;
        if (url.contains("_160.mp4")) return 160;
        if (url.contains("_96.mp4")) return 96;
        return 0;
    }

    private static boolean is320(Map<String, Object> root) {
        final List<Object> songs = Json.arr(root, "songs");
        if (songs != null && !songs.isEmpty() && songs.get(0) instanceof Map) {
            //noinspection unchecked
            final Map<String, Object> mi = Json.obj((Map<String, Object>) songs.get(0), "more_info");
            if (mi != null) return Json.bool(mi, "320kbps", false);
        }
        return true; // optimistic: agar info nahi hai to 320 try karo (galat ho to 96 fallback)
    }

    /* ------------------------------------------------------------------ *
     *  helpers
     * ------------------------------------------------------------------ */

    /** HTML entities (&#039; etc.) hataata hai — Saavn titles me aksar aate hain. */
    public static String clean(String s) {
        if (s == null) return null;
        String r = s.replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'");
        // numeric entities
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            final char c = r.charAt(i);
            if (c == '&' && i + 5 < r.length() && r.charAt(i + 1) == '#') {
                int j = i + 2;
                final StringBuilder num = new StringBuilder();
                while (j < r.length() && Character.isDigit(r.charAt(j))) {
                    num.append(r.charAt(j));
                    j++;
                }
                if (j < r.length() && r.charAt(j) == ';' && num.length() > 0) {
                    try {
                        sb.append((char) Integer.parseInt(num.toString()));
                        i = j;
                        continue;
                    } catch (Exception ignore) { }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 150x150 thumbnail ko 500x500 bana deta hai. */
    public static String upgradeImage(String image) {
        if (image == null) return null;
        return image.replace("150x150", "500x500").replace("50x50", "500x500");
    }

    /**
     * Chhota Base64 decoder — pure Java.
     * (java.util.Base64 Android API 26+ par hi milta hai, aur minSdk 21 hai —
     *  isliye apna decoder rakha hai taaki code har jagah + desktop par chale.)
     */
    public static byte[] base64Decode(String s) {
        if (s == null) return new byte[0];
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        final StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (alphabet.indexOf(c) >= 0 || c == '-' || c == '_') clean.append(c == '-' ? '+' : c == '_' ? '/' : c);
        }
        final int len = clean.length();
        final byte[] out = new byte[(len * 3) / 4];
        int outPos = 0;
        int buffer = 0, bits = 0;
        for (int i = 0; i < len; i++) {
            final int v = alphabet.indexOf(clean.charAt(i));
            if (v < 0) continue;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outPos++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        if (outPos == out.length) return out;
        final byte[] trimmed = new byte[outPos];
        System.arraycopy(out, 0, trimmed, 0, outPos);
        return trimmed;
    }

    public static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8");
            final int code = conn.getResponseCode();
            final InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new Exception("HTTP " + code);
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            if (code >= 400) throw new Exception("HTTP " + code + ": " + bos.toString("UTF-8"));
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // jarurat padne par: text file read
    static String readAll(InputStream in) throws Exception {
        final BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        final StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }
}
