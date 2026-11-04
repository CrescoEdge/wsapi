package io.cresco.wsapi.netty;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts message parameters in log lines before wsapi forwards them to a log-stream client, by allowlist: only the
 * routing and status parameters named in {@link #ALLOWED} keep their values; every other parameter's value becomes
 * {@link #REDACTED}, whatever it is called. (A denylist of secret-looking names missed parameters such as
 * {@code note}: a client's own data is not for other clients' eyes.)
 *
 * <p>Two shapes are recognised, as the controller logs them: a Java map, {@code {action=x, note=y}} (MsgEvent params),
 * and JSON string pairs, {@code "note":"y"}. Text outside them is unchanged.
 */
final class LogRedaction {

    static final String REDACTED = "<redacted>";

    /** Parameters whose values a log line may show: routing, identity and status, never a client's data. */
    static final Set<String> ALLOWED = Set.of("action", "status", "status_code", "status_desc", "type", "msg_type",
            "src_region", "src_agent", "src_plugin", "dst_region", "dst_agent", "dst_plugin", "region_id", "agent_id",
            "plugin_id", "is_rpc", "is_global", "is_regional", "ttl", "pluginname", "pluginid", "client_rpc_id",
            "cresco_caller", "cresco_caller_tenant", "cresco_caller_roles", "cresco_tenant", "transfer_id", "stream_name",
            "inode_id", "jarfile", "version", "md5", "configtype", "globalcmd", "regioncmd", "region_name", "agent_name");

    private static final Pattern MAP_KEY = Pattern.compile("([{,]\\s?)([A-Za-z_][A-Za-z0-9_.:-]*)=");
    private static final Pattern JSON_PAIR = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_.:-]*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private LogRedaction() {}

    static boolean allowed(String key) {
        return ALLOWED.contains(key) || key.startsWith("callId-");
    }

    static String redact(String line) {
        if (line == null || line.isEmpty()) return line;
        return redactJson(redactMaps(line));
    }

    /** Every {@code {k=v, ...}} span: values of keys not allowed are replaced (an unclosed one runs to the end). */
    static String redactMaps(String line) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            int open = line.indexOf('{', i);
            if (open < 0) {
                out.append(line, i, line.length());
                break;
            }
            out.append(line, i, open);
            int close = matching(line, open);
            if (close < 0) {                                  // a cut line: redact to its end
                String body = redactMapBody(line.substring(open) + "}");
                out.append(body, 0, body.length() - 1);
                break;
            }
            out.append(redactMapBody(line.substring(open, close + 1)));
            i = close + 1;
        }
        return out.toString();
    }

    private static int matching(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return k;
        }
        return -1;
    }

    /** {@code {a=1, note=x, b={c=2}}}: keys found at top level; each value runs to the next key or the end. */
    private static String redactMapBody(String map) {
        Matcher m = MAP_KEY.matcher(map);
        java.util.List<int[]> keys = new java.util.ArrayList<>();      // {valueStart, keyStart, keyEnd}
        while (m.find()) {
            if (depth(map, m.start()) != 1) continue;               // a nested map is handled as part of its value
            keys.add(new int[]{m.end(), m.start(2), m.end(2)});
        }
        if (keys.isEmpty()) return map;
        StringBuilder out = new StringBuilder(map.length());
        out.append(map, 0, keys.get(0)[0]);
        for (int k = 0; k < keys.size(); k++) {
            int vs = keys.get(k)[0];
            int ve = k + 1 < keys.size() ? keys.get(k + 1)[1] - sepLength(map, keys.get(k + 1)[1]) : map.length() - 1;
            String key = map.substring(keys.get(k)[1], keys.get(k)[2]);
            out.append(allowed(key) ? map.substring(vs, Math.max(vs, ve)) : REDACTED);
            if (k + 1 < keys.size()) out.append(map, Math.max(vs, ve), keys.get(k + 1)[0]);
            else out.append(map, Math.max(vs, ve), map.length());
        }
        return out.toString();
    }

    private static int depth(String s, int pos) {
        int d = 0;
        for (int k = 0; k <= pos && k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d;
    }

    /** The separator before a key: ", " or "," (or "{" for the first, which is not a separator). */
    private static int sepLength(String map, int keyStart) {
        int n = 0;
        int k = keyStart - 1;
        if (k >= 0 && map.charAt(k) == ' ') { n++; k--; }
        if (k >= 0 && map.charAt(k) == ',') n++;
        return n;
    }

    /** {@code "k":"v"} pairs: values of keys not allowed are replaced. */
    static String redactJson(String line) {
        Matcher m = JSON_PAIR.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String repl = allowed(m.group(1)) ? m.group(0) : "\"" + m.group(1) + "\":\"" + REDACTED + "\"";
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }
}
