package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.cresco.library.messaging.MsgEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps plugin config secrets out of what the API socket sends back (GaiaKeep OUT-03). The controller
 * redacts its own exports and replies at the source (ConfigRedaction); this is the last hop before a client,
 * for a reply that carries a plugin's config back anyway: a responder that echoes the request's
 * {@code configparams} (the whole config, compressed or plain JSON) hands its secrets to the client and to
 * every log the client keeps.
 *
 * <p>Only {@code configparams} is touched: its secret-named keys ({@link FailureEcho#SECRET_KEY}, the
 * controller's rule) become {@link FailureEcho#REDACTED}, embedded secret name=value parameters in its string
 * values are redacted, and the rest is kept in the same encoding (compressed stays compressed). A
 * configparams that cannot be read as a JSON object is withheld ("{}"): what cannot be read cannot be shown to
 * be safe. Other reply params are a plugin's own API and are passed through.
 */
final class ReplyRedaction {

    static final String CONFIG = "configparams";
    private static final Gson GSON = new Gson();

    private ReplyRedaction() {}

    /** Redacts the reply's configparams in place; returns the reply. */
    static MsgEvent redact(MsgEvent reply) {
        if (reply == null) return null;
        String v = reply.getParam(CONFIG);
        if (v == null) return reply;
        if (v.startsWith("H4sI")) {                              // MsgEvent.setCompressedParam: gzip base64
            String json;
            try {
                json = reply.getCompressedParam(CONFIG);
            } catch (RuntimeException notGzipBase64) {
                json = null;                                     // unreadable: withheld below
            }
            reply.setCompressedParam(CONFIG, redactJson(json));
        } else {
            reply.setParam(CONFIG, redactJson(v));
        }
        return reply;
    }

    /** A config JSON object with every secret value replaced, at any depth; anything else is withheld as "{}". */
    static String redactJson(String json) {
        if (json == null) return "{}";
        try {
            JsonElement e = new JsonParser().parse(json);
            if (!e.isJsonObject()) return "{}";
            redactTree(e);
            return GSON.toJson(e);
        } catch (RuntimeException unreadable) {
            return "{}";
        }
    }

    private static void redactTree(JsonElement e) {
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement x = a.get(i);
                if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) a.set(i, new JsonPrimitive(redactString(x.getAsString())));
                else redactTree(x);
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, JsonElement> en : o.entrySet()) keys.add(en.getKey());
            for (String k : keys) {
                JsonElement v = o.get(k);
                if (FailureEcho.isSecretKey(k)) o.add(k, new JsonPrimitive(FailureEcho.REDACTED));
                else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) o.add(k, new JsonPrimitive(redactString(v.getAsString())));
                else redactTree(v);
            }
        }
    }

    /** A string value: a nested JSON object (config inside config) is redacted as JSON, anything else as text. */
    private static String redactString(String s) {
        String t = s.trim();
        if (t.startsWith("{")) {
            try {
                JsonElement inner = new JsonParser().parse(t);
                if (inner.isJsonObject()) { redactTree(inner); return GSON.toJson(inner); }
            } catch (RuntimeException notJson) {
                // fall through to text
            }
        }
        return FailureEcho.redactText(s);
    }
}
