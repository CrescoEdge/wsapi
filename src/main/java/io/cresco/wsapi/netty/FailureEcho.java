package io.cresco.wsapi.netty;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which request params a failed RPC's reply carries back. The reply exists so a correlating client
 * (pycrescolib's client_rpc_id) matches it instead of waiting out its own timeout; it must never be
 * bigger than a client frame, and never repeat a secret. Echoing the request verbatim did both wrong:
 * a timed-out plugin upload echoed its 1.8 MB jardata, past pycrescolib's 1 MiB frame limit, and the
 * client's connection was closed.
 *
 * <ul>
 *   <li>Secret-named params (the controller's ConfigRedaction rule, {@link #SECRET_KEY}) are left out.</li>
 *   <li>Data params (MsgEvent data/compressed params: base64, gzip base64, jardata) and any value over
 *       {@link #MAX_VALUE_BYTES} are replaced by a marker naming their size.</li>
 *   <li>Echoed values are capped at {@link #MAX_TOTAL_BYTES} in total; past it, params are dropped and
 *       counted in {@code echo_omitted}. The correlation id goes first, so it always fits.</li>
 *   <li>Embedded secret-looking name=value parameters inside a kept value are redacted.</li>
 * </ul>
 */
final class FailureEcho {

    static final int MAX_VALUE_BYTES = 1024;
    static final int MAX_TOTAL_BYTES = 16 * 1024;
    static final String REDACTED = "[REDACTED]";

    /** Same rule as the controller's io.cresco.agent.db.ConfigRedaction.SECRET_KEY (wsapi does not import the controller). */
    static final Pattern SECRET_KEY = Pattern.compile(
            "(?i:secret|password|passphrase|token|_key$)"
            + "|(?i:(?:^|[_.-])pin(?:[_.-]|$))"
            + "|(?:^|[_.-])(?:pin|Pin)(?=[A-Z0-9])"
            + "|[a-z0-9](?:Pin|PIN)(?![a-z])");
    /** Same as ConfigRedaction.EMBEDDED_PARAM: name=value inside a URI query, transport options or a JDBC URL. */
    private static final Pattern EMBEDDED_PARAM = Pattern.compile("([A-Za-z0-9_.\\-]+)=([^&;,)\\s\"']*)");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=\\r\\n]+");
    /** Params the clients always send as data (pycrescolib agents.upload/add_plugin_repo: jardata). */
    private static final List<String> DATA_KEYS = List.of("jardata");
    /** Sent first so they are never pushed out by the total cap. */
    private static final List<String> FIRST = List.of("client_rpc_id", "action");

    private FailureEcho() {}

    static boolean isSecretKey(String key) { return key != null && SECRET_KEY.matcher(key).find(); }

    /** A MsgEvent data or compressed param: gzip-base64 (H4sI...), a known data key, or long base64. */
    static boolean isData(String key, String v) {
        if (DATA_KEYS.contains(key)) return true;
        if (v.length() >= 8 && v.startsWith("H4sI") && BASE64.matcher(v).matches()) return true;
        return v.length() >= 256 && BASE64.matcher(v).matches();
    }

    static String redactText(String text) {
        if (text == null || text.indexOf('=') < 0) return text;
        Matcher m = EMBEDDED_PARAM.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = isSecretKey(m.group(1)) ? m.group(1) + "=" + REDACTED : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The params a failure reply carries back, in reply order. */
    static Map<String, String> params(Map<String, String> payload) {
        Map<String, String> out = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) return out;
        List<String> keys = new ArrayList<>();
        for (String k : FIRST) if (payload.containsKey(k)) keys.add(k);
        for (String k : payload.keySet()) if (!FIRST.contains(k)) keys.add(k);
        int total = 0, omitted = 0;
        for (String k : keys) {
            String v = payload.get(k);
            if (k == null || isSecretKey(k)) continue;              // never echoed, not even as a marker
            String e;
            if (v == null) e = null;
            else {
                int n = v.getBytes(StandardCharsets.UTF_8).length;
                if (isData(k, v)) e = "[omitted: data param, " + n + " bytes]";
                else if (n > MAX_VALUE_BYTES) e = "[omitted: " + n + " bytes, over the " + MAX_VALUE_BYTES + "-byte echo cap]";
                else e = redactText(v);
            }
            int cost = k.length() + (e == null ? 4 : e.getBytes(StandardCharsets.UTF_8).length);
            if (total + cost > MAX_TOTAL_BYTES) { omitted++; continue; }
            total += cost;
            out.put(k, e);
        }
        if (omitted > 0) out.put("echo_omitted", omitted + " param(s) past the " + MAX_TOTAL_BYTES + "-byte echo cap");
        return out;
    }
}
