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
