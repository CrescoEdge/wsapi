package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;

import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The text frames wsapi sends a dataplane client for messages that are not plain text or data chunks.
 *
 * <ul>
 *   <li>A {@link MapMessage} (the executor's {@code execution_log} and {@code delete_exchange} events, among others)
 *       becomes a text frame with the JSON object of its entries: strings, numbers and booleans as such, bytes as
 *       base64. wsapi used to drop them, so a client never saw a process end.</li>
 *   <li>A filerepo {@code streamfile} terminal frame (an empty {@link BytesMessage} with {@code transfer_end=true})
 *       becomes a text frame with the JSON object of its transfer properties ({@link #TRANSFER_PROPERTIES}): the
 *       transfer's status and, on success, the SHA-384 of the bytes sent, which the client verifies. As a binary
 *       frame it carried only the chunk header, so the status and the hash were lost.</li>
 * </ul>
 *
 * <p><b>Envelope frames</b> (a handshake with {@code "envelope": "true"}): every frame carries its verified writer, so a
 * client can tell an executor's {@code execution_log} from a user's text that merely looks like one. A text, map or
 * terminal message becomes a JSON text frame
 * {@code {"kind": "text"|"map"|"transfer_end", "writer", "writer_roles", "writer_tenant", "properties": {...},
 * "text": "..." | "map": {...}}}; a data message becomes a binary frame: a 4-byte big-endian header length, the UTF-8
 * JSON header {@code {"kind": "bytes", "writer", ..., "properties"}}, then the payload as published. The writer is the
 * API user wsapi published for ({@code cresco_writer}, which the controller keeps only from wsapi and only for its own
 * tenant's users), else the bundle or controller the sending controller stamped ({@code cresco_caller}); null when
 * neither is present (an older controller).
 */
final class DataPlaneFrames {

    private static final Gson GSON = new Gson();

    /** The properties a streamfile terminal frame may carry (filerepo's frame properties). */
    static final List<String> TRANSFER_PROPERTIES = List.of("transfer_id", "transfer_end", "transfer_status", "transfer_desc",
            "total_bytes", "sha384", "seq_num", "offset", "start_byte", "byte_length");

    private DataPlaneFrames() {}

    /** True for a streamfile terminal frame. */
    static boolean isTransferEnd(Message msg) throws JMSException {
        return msg instanceof BytesMessage && "true".equals(msg.getStringProperty("transfer_end"));
    }

    /** The JSON text of a MapMessage's entries. */
    static String mapJson(MapMessage msg) throws JMSException {
        Map<String, Object> out = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Enumeration<String> names = msg.getMapNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            Object v = msg.getObject(n);
            if (v instanceof byte[]) v = Base64.getEncoder().encodeToString((byte[]) v);
            else if (v instanceof Character) v = String.valueOf(v);
            out.put(n, v);
        }
        return GSON.toJson(out);
    }

    static final String CALLER = "cresco_caller", CALLER_TENANT = "cresco_caller_tenant", CALLER_ROLES = "cresco_caller_roles";

    /**
     * The envelope fields of a message: its writer and its properties -- no JMS* headers, and none of infrastructure's
     * {@code cresco_*} properties but the stream's {@code cresco_seq}/{@code cresco_gap} ({@link InternalParams}).
     */
    static Map<String, Object> envelope(String kind, Message msg) throws JMSException {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        String user = msg.getStringProperty(DataPlaneWsHandler.WRITER);
        String writer, roles, tenant;
        if (user != null && !user.isEmpty()) {
            writer = user;
            roles = msg.getStringProperty(DataPlaneWsHandler.WRITER_ROLES);
            int slash = user.indexOf('/');
            tenant = user.startsWith("api:") && slash > 4 ? user.substring(4, slash) : null;
        } else {
            String caller = msg.getStringProperty(CALLER);
            writer = caller == null || caller.isEmpty() ? null : caller;
            roles = writer == null ? null : msg.getStringProperty(CALLER_ROLES);
            tenant = writer == null ? null : msg.getStringProperty(CALLER_TENANT);
        }
        e.put("writer", writer);
        e.put("writer_roles", roles);
        e.put("writer_tenant", tenant);
        Map<String, Object> props = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Enumeration<String> names = msg.getPropertyNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            if (InternalParams.internalProperty(n)) continue;  // JMS headers, infrastructure's cresco_* (not the stream's seq/gap)
            Object v = msg.getObjectProperty(n);
            props.put(n, v instanceof Character ? String.valueOf(v) : v);
        }
        e.put("properties", props);
        return e;
    }

    /** The envelope text frame of a text, map or terminal message. */
    static String envelopeJson(Message msg) throws JMSException {
        if (msg instanceof jakarta.jms.TextMessage) {
            Map<String, Object> e = envelope("text", msg);
            e.put("text", ((jakarta.jms.TextMessage) msg).getText());
            return GSON.toJson(e);
        }
        // the map as a JSON tree: its numbers keep their text (through a Map every number became a double, 42 -> 42.0)
        if (msg instanceof MapMessage) {
            JsonObject e = GSON.toJsonTree(envelope("map", msg)).getAsJsonObject();
            e.add("map", GSON.fromJson(mapJson((MapMessage) msg), JsonElement.class));
            return GSON.toJson(e);
        }
        JsonObject e = GSON.toJsonTree(envelope("transfer_end", msg)).getAsJsonObject();
        e.add("map", GSON.fromJson(transferEndJson(msg), JsonElement.class));
        return GSON.toJson(e);
    }

    /** The envelope binary frame of a data message: header length (4 bytes, big-endian), JSON header, payload. */
    static byte[] envelopeBytes(Message msg, byte[] payload) throws JMSException {
        byte[] header = GSON.toJson(envelope("bytes", msg)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[4 + header.length + payload.length];
        java.nio.ByteBuffer.wrap(out).putInt(header.length).put(header).put(payload);
        return out;
    }

    /** The key a plain map or terminal frame names its writer under (and {@code cresco_writer_roles}). */
    static final String WRITER_KEY = "cresco_writer";

    /**
     * A plain map or terminal frame's JSON with its writer added ({@code cresco_writer}, {@code cresco_writer_roles}), so a
     * client without envelope frames can still tell who wrote an event; existing clients ignore the extra keys. A user
     * cannot forge them: a client's text frame that is a JSON object with that key is refused ({@link #claimsWriter}).
     * The keys are added to the parsed JSON tree, whose numbers keep their original text: a long 42 stays {@code 42} and
     * 9007199254740993 stays exact (a round trip through a Map made every number a double: 42.0, and large longs rounded).
     */
    static String withWriter(String json, Message msg) throws JMSException {
        Map<String, Object> e = envelope("map", msg);
        if (e.get("writer") == null) return json;
        JsonElement tree = GSON.fromJson(json, JsonElement.class);
        if (!tree.isJsonObject()) return json;
        JsonObject m = tree.getAsJsonObject();
        m.addProperty(WRITER_KEY, String.valueOf(e.get("writer")));
        if (e.get("writer_roles") != null) m.addProperty(WRITER_KEY + "_roles", String.valueOf(e.get("writer_roles")));
        return GSON.toJson(m);
    }

    /** Nesting deeper than this, in a frame that may name the writer key, is refused rather than parsed. */
    static final int MAX_CHECKED_DEPTH = 1024;

    /**
     * Does a client's text claim a writer: is it a JSON object with a top-level key {@code cresco_writer...} (any case),
     * as any client's parser would decode it? The frame is parsed, so a key spelled with escapes
     * (a backslash-u escape for the underscore, say) is found as what it decodes to, and a key given twice is found whichever copy a
     * parser keeps. Leniently, as Gson reads it (single quotes, unquoted names, comments), which accepts everything a strict
     * parser (Python's json, JavaScript's JSON.parse) accepts. Text that cannot decode to the key (checked first, cheaply,
     * with its backslash-u escapes decoded) is not parsed; such a text nested more than {@link #MAX_CHECKED_DEPTH} levels
     * deep is refused rather than parsed. Text that is not a JSON object, or not JSON, is no claim.
     */
    static boolean claimsWriter(String text) {
        if (text == null) return false;
        int i = 0, n = text.length();
        while (i < n && (Character.isWhitespace(text.charAt(i)) || Character.isSpaceChar(text.charAt(i)) || text.charAt(i) == '\ufeff')) i++;
        if (i >= n || text.charAt(i) != '{') return false;
        if (!unescaped(text, i).toLowerCase(java.util.Locale.ROOT).contains(WRITER_KEY)) return false;
        if (depth(text, i) > MAX_CHECKED_DEPTH) return true;
        try (JsonReader r = new JsonReader(new java.io.StringReader(text.substring(i)))) {
            r.setLenient(true);
            r.beginObject();
            while (r.hasNext()) {
                if (r.nextName().toLowerCase(java.util.Locale.ROOT).startsWith(WRITER_KEY)) return true;
                r.skipValue();
            }
            return false;
        } catch (java.io.IOException | RuntimeException notAnObject) {
            return false;                    // no parser makes an object of it (Gson's lenient mode accepts the most)
        }
    }

    /** The text from {@code from} with its backslash-u escapes decoded (the only escapes that can spell a letter). */
    private static String unescaped(String t, int from) {
        StringBuilder sb = new StringBuilder(t.length() - from);
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 5 < t.length() && t.charAt(i + 1) == 'u') {
                try {
                    sb.append((char) Integer.parseInt(t.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException notAnEscape) {
                    // as it is
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** The deepest nesting of {@code { [} outside quoted strings (an upper bound: brackets in comments count too). */
    private static int depth(String t, int from) {
        int d = 0, max = 0;
        char quote = 0;
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                if (++d > max) max = d;
            } else if (c == '}' || c == ']') {
                d--;
            }
        }
        return max;
    }

    /** The JSON text of a terminal frame's transfer properties. */
    static String transferEndJson(Message msg) throws JMSException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : TRANSFER_PROPERTIES) {
            String v = msg.getStringProperty(p);
            if (v != null) out.put(p, v);
        }
        return GSON.toJson(out);
    }
}
