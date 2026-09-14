package org.telegram.messenger.gramify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gramify minimal JSON parser — pure Java (koi dependency nahi).
 *
 * Kyun? Kyunki isse SaavnApi.java ko Android ke bahar bhi compile + test kiya ja
 * sakta hai (dekho: gramify/test/SaavnSmokeTest.java). Android par org.json
 * available hota hai, par ye chhota parser har jagah chalta hai.
 *
 * Supports: object, array, string (with unicode uXXXX escapes), number, true/false/null.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    /** @return Map<String,Object> | List<Object> | String | Double | Boolean | null */
    public static Object parse(String text) {
        if (text == null) return null;
        final Json p = new Json(text);
        p.ws();
        final Object v = p.value();
        return v;
    }

    public static Map<String, Object> parseObject(String text) {
        final Object o = parse(text);
        //noinspection unchecked
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /* ------------------------------------------------------------------ */

    private void ws() {
        while (pos < src.length()) {
            final char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
            else break;
        }
    }

    private Object value() {
        ws();
        if (pos >= src.length()) return null;
        final char c = src.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't':
                expect("true"); return Boolean.TRUE;
            case 'f':
                expect("false"); return Boolean.FALSE;
            case 'n':
                expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (src.startsWith(word, pos)) pos += word.length();
    }

    private Map<String, Object> object() {
        final Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
        while (pos < src.length()) {
            ws();
            final String key = string();
            ws();
            if (pos < src.length() && src.charAt(pos) == ':') pos++;
            map.put(key, value());
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; break; }
            break;
        }
        return map;
    }

    private List<Object> array() {
        final List<Object> list = new ArrayList<>();
        pos++; // [
        ws();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
        while (pos < src.length()) {
            list.add(value());
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; break; }
            break;
        }
        return list;
    }

    private String string() {
        final StringBuilder sb = new StringBuilder();
        ws();
        if (pos < src.length() && src.charAt(pos) == '"') pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < src.length()) {
                final char e = src.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '/': sb.append('/'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            try {
                                sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            } catch (Exception ignore) {
                            }
                            pos += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object number() {
        final int start = pos;
        while (pos < src.length()) {
            final char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
            else break;
        }
        if (pos == start) { pos++; return null; }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (Exception e) {
            return null;
        }
    }

    /* ----------------------------- helpers ---------------------------- */

    public static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        final Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        final String s = str(m, key);
        return (s == null || s.isEmpty()) ? def : s;
    }

    public static long num(Map<String, Object> m, String key, long def) {
        if (m == null) return def;
        final Object v = m.get(key);
        if (v instanceof Double) return (long) (double) (Double) v;
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Exception ignore) { }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        if (m == null) return def;
        final Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        if (m == null) return null;
        final Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        if (m == null) return null;
        final Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }
}
