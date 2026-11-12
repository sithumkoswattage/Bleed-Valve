import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One STATE message from the server, parsed into something the UI can read.
 *
 * The server sends flat JSON, so a tiny hand-written parser is enough and
 * keeps the project free of external libraries.
 *
 * This object is IMMUTABLE once built: the Listener thread creates it,
 * the UI thread reads it. Nothing is ever modified after construction,
 * so the two threads can never see a half-updated object.
 */
public class Snapshot {
    public final Map<String, String> values = new HashMap<>();
    public final List<Contact> contacts = new ArrayList<>();

    /**
     * One ship as the eye receives it: a bearing, an angular height and an angular width.
     * The "truth" fields are only filled in when the server is in training mode.
     */
    public static class Contact {
        public final int id, cls, dir;
        public final double bearing, mast, wid;          // mast and wid in milliradians
        public final boolean near;                       // bow toward us (angle on the bow under 90)
        public final String name, ai;                    // training only
        public final double range, heading, aob, kn;     // training only (range 0 = not sent)
        Contact(Map<String, String> m) {
            id = (int) num(m, "id"); cls = (int) num(m, "cls"); dir = (int) num(m, "dir");
            bearing = num(m, "bearing"); mast = num(m, "mast"); wid = num(m, "wid");
            near = "true".equals(m.get("near"));
            name = m.getOrDefault("name", ""); ai = m.getOrDefault("ai", "");
            range = num(m, "range"); heading = num(m, "heading"); aob = num(m, "aob"); kn = num(m, "kn");
        }
    }

    /** @param json the part after "STATE|" */
    public static Snapshot parse(String json) {
        Snapshot s = new Snapshot();

        int ci = json.indexOf("\"contacts\":[");
        String flat = json;
        if (ci >= 0) {
            int end = json.indexOf(']', ci);
            String arr = json.substring(ci + 12, end);
            flat = json.substring(0, ci) + json.substring(end + 1);
            for (String obj : splitObjects(arr)) {
                s.contacts.add(new Contact(flatPairs(obj)));
            }
        }
        s.values.putAll(flatPairs(flat));
        return s;
    }

    /** Splits `{...},{...}` into the individual objects. */
    private static List<String> splitObjects(String arr) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0) out.add(arr.substring(start, i + 1)); }
        }
        return out;
    }

    /** Reads `"key":value` pairs out of one flat object. */
    private static Map<String, String> flatPairs(String src) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while ((i = src.indexOf('"', i)) >= 0) {
            int ke = src.indexOf('"', i + 1);
            if (ke < 0) break;
            String key = src.substring(i + 1, ke);
            int colon = src.indexOf(':', ke);
            if (colon < 0) break;
            int p = colon + 1;
            while (p < src.length() && src.charAt(p) == ' ') p++;
            if (p >= src.length()) break;
            int end;
            if (src.charAt(p) == '"') {                       // string value
                end = src.indexOf('"', p + 1);
                if (end < 0) break;
                m.put(key, src.substring(p + 1, end));
                i = end + 1;
            } else {                                          // number or true/false
                end = p;
                while (end < src.length() && ",}]".indexOf(src.charAt(end)) < 0) end++;
                m.put(key, src.substring(p, end).trim());
                i = end;
            }
        }
        return m;
    }

    private static double num(Map<String, String> m, String k) {
        try { return Double.parseDouble(m.getOrDefault(k, "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    public double d(String key) { return num(values, key); }
    public int i(String key) { return (int) d(key); }
    public boolean b(String key) { return "true".equals(values.get(key)); }
    public String s(String key) { return values.getOrDefault(key, ""); }
}
