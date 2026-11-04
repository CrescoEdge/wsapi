package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.wsapi.auth.Authenticator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.jms.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 3 (review/api-20261104): a user may set only {@code type} beside its stream_name, and may not
 * write {@code type=output} on a granted stream. The executor writes three types on a process's stream: output, error and
 * event (its execution_log / delete_exchange events). On a stream granted to it (e.g. a job's output it should follow),
 * a user still writes {@code type=error} lines that read as the process's stderr, and {@code type=event} text such as
 * {"cmd":"execution_log","log":"Exit Code: 0"}, which a client without the opt-in envelope cannot tell from the
 * executor's own exit event.
 */
class ReviewApi3DataplaneTest {

    static Authenticator.Principal withGrant(String user, String grant) throws Exception {
        java.lang.reflect.Constructor<Authenticator.Principal> c = Authenticator.Principal.class.getDeclaredConstructor(
                String.class, String.class, String.class, boolean.class, List.class);
        c.setAccessible(true);
        return c.newInstance("tenant-a", user, "user", false, List.of(grant));
    }

    @Test
    void aUserCannotPoseAsTheExecutorOnAGrantedStream() throws Exception {
        List<String> posed = new ArrayList<>();
        for (String type : new String[]{"output", "error", "event"}) {
            ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
            PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
            EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, withGrant("bob", "job-7*"),
                    ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
            Map<String, String> hs = new HashMap<>();
            hs.put("ident_key", "stream_name");
            hs.put("ident_id", "job-7");
            hs.put("io_type_key", "type");
            hs.put("input_id", type);
            ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(hs)));
            Map<String, String> reply = ReviewApiDataplaneTest.reply(ch);
            ch.writeInbound(new TextWebSocketFrame("{\"cmd\":\"execution_log\",\"log\":\"Exit Code: 0\"}"));
            for (Message m : rec.sent) {
                if ("job-7".equals(m.getStringProperty("stream_name")) && type.equals(m.getStringProperty("type"))) {
                    posed.add("type=" + type + " (activation " + reply.get("status_code") + ")");
                }
            }
        }
        assertEquals(List.of(), posed, "bob (role user, granted job-7*) wrote executor-typed messages into job-7");
    }
}
