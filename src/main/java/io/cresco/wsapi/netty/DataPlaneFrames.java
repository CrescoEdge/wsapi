package io.cresco.wsapi.netty;

import com.google.gson.Gson;
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

    /** The envelope fields of a message: its writer and its properties (no JMS* headers). */
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
            if (n.startsWith("JMS")) continue;
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
        if (msg instanceof MapMessage) {
            Map<String, Object> e = envelope("map", msg);
            e.put("map", GSON.fromJson(mapJson((MapMessage) msg), Map.class));
            return GSON.toJson(e);
        }
        Map<String, Object> e = envelope("transfer_end", msg);
        e.put("map", GSON.fromJson(transferEndJson(msg), Map.class));
        return GSON.toJson(e);
    }

    /** The envelope binary frame of a data message: header length (4 bytes, big-endian), JSON header, payload. */
    static byte[] envelopeBytes(Message msg, byte[] payload) throws JMSException {
        byte[] header = GSON.toJson(envelope("bytes", msg)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[4 + header.length + payload.length];
        java.nio.ByteBuffer.wrap(out).putInt(header.length).put(header).put(payload);
        return out;
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
