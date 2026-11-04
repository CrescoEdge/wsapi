package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.wsapi.websockets.StreamInfo;
import org.apache.activemq.command.ActiveMQBytesMessage;
import org.apache.activemq.command.ActiveMQMapMessage;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.filter.BooleanExpression;
import org.apache.activemq.filter.MessageEvaluationContext;
import org.apache.activemq.selector.SelectorParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a wsapi dataplane subscriber may receive, judged by the broker's own selector engine (ActiveMQ), and the text
 * frames for map events and streamfile terminal frames.
 */
class DataPlaneStdinAndFramesTest {

    static boolean matches(String selector, ActiveMQMessage m) throws Exception {
        BooleanExpression e = SelectorParser.parse(selector);
        MessageEvaluationContext ctx = new MessageEvaluationContext();
        ctx.setMessageReference(m);
        return e.matches(ctx);
    }

    static ActiveMQTextMessage msg(String stream, String type) throws Exception {
        ActiveMQTextMessage m = new ActiveMQTextMessage();
        m.setText("line");
        m.setStringProperty("stream_name", stream);
        if (type != null) m.setStringProperty("type", type);
        return m;
    }

    static StreamInfo json(String key, String id) {
        StreamInfo si = new StreamInfo("t", key, id);
        si.setIoTypeKey("type");
        si.setInputId("in");
        return si;
    }

    /** The controller's StdinGuard pattern (controller hardening 7d7cfd5): a selector it matches is refused. */
    static final java.util.regex.Pattern CONTROLLER_NAMES_STDIN = java.util.regex.Pattern.compile("'input(-|'|%|_)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    @Test
    void wsapiSubscribesWithTheClientsSelectorOnlyAndNeverNamesStdin() throws Exception {
        String[] subscriptions = {
                StreamSelectors.subscriptionSelector(json("stream_name", "runner1")),
                StreamSelectors.subscriptionSelector(new StreamInfo("t", "stream_name='runner1'")),
                StreamSelectors.subscriptionSelector(new StreamInfo("t", "stream_name='runner1' AND type <> 'output'")),
                StreamSelectors.subscriptionSelector(new StreamInfo("t", "stream_name='gk-1' AND type='data'"))};
        assertEquals("stream_name='runner1'", subscriptions[0]);
        assertEquals("stream_name='runner1'", subscriptions[1]);
        for (String sel : subscriptions) {
            assertFalse(CONTROLLER_NAMES_STDIN.matcher(sel).find(), "the controller would refuse " + sel);
        }
        String plain = subscriptions[0];
        assertTrue(matches(plain, msg("runner1", "output")));
        assertTrue(matches(plain, msg("runner1", null)), "a message without a type");
        assertFalse(matches(plain, msg("runner2", "output")), "still only the named stream");
        // the client's own selector rules are unchanged: a refused query is still refused
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.subscriptionSelector(new StreamInfo("t", "a='1' OR b='2'")));
    }

    @Test
    void mapEventsBecomeJsonTextFrames() throws Exception {
        ActiveMQMapMessage m = new ActiveMQMapMessage();
        m.setString("cmd", "execution_log");
        m.setString("log", "Exit Code: 3");
        m.setLong("n", 42L);
        m.setBoolean("ok", true);
        m.setBytes("raw", new byte[]{1, 2, 3});
        @SuppressWarnings("unchecked")
        Map<String, Object> j = new Gson().fromJson(DataPlaneFrames.mapJson(m), Map.class);
        assertEquals("execution_log", j.get("cmd"));
        assertEquals("Exit Code: 3", j.get("log"));
        assertEquals(42.0, j.get("n"));
        assertEquals(true, j.get("ok"));
        assertEquals("AQID", j.get("raw"));
        assertFalse(DataPlaneFrames.isTransferEnd(m));
    }

    @Test
    void aStreamfileTerminalFrameBecomesJsonWithItsStatusAndHash() throws Exception {
        ActiveMQBytesMessage end = new ActiveMQBytesMessage();
        end.setStringProperty("stream_name", "x");
        end.setStringProperty("transfer_id", "t1");
        end.setStringProperty("seq_num", "3");
        end.setStringProperty("transfer_end", "true");
        end.setStringProperty("transfer_status", "10");
        end.setStringProperty("transfer_desc", "complete");
        end.setStringProperty("total_bytes", "700000");
        end.setStringProperty("sha384", "ab".repeat(48));
        assertTrue(DataPlaneFrames.isTransferEnd(end));
        @SuppressWarnings("unchecked")
        Map<String, Object> j = new Gson().fromJson(DataPlaneFrames.transferEndJson(end), Map.class);
        assertEquals("true", j.get("transfer_end"));
        assertEquals("10", j.get("transfer_status"));
        assertEquals("700000", j.get("total_bytes"));
        assertEquals("ab".repeat(48), j.get("sha384"));
        assertEquals("t1", j.get("transfer_id"));
        assertNull(j.get("stream_name"), "only the transfer's own properties");
        ActiveMQBytesMessage chunk = new ActiveMQBytesMessage();
        chunk.setStringProperty("transfer_id", "t1");
        assertFalse(DataPlaneFrames.isTransferEnd(chunk));
    }

    @Test
    void onlyCallersOfTheAgentsTenantUseItsDataplane() throws Exception {
        java.lang.reflect.Constructor<io.cresco.wsapi.auth.Authenticator.Principal> c = io.cresco.wsapi.auth.Authenticator.Principal.class
                .getDeclaredConstructor(String.class, String.class, String.class, boolean.class);
        c.setAccessible(true);
        assertNull(DataPlaneWsHandler.tenantRefusal(c.newInstance("tenant-a", "bob", "user", false), "tenant-a"));
        assertNotNull(DataPlaneWsHandler.tenantRefusal(c.newInstance("ops", "root", "admin", false), "tenant-a"),
                "even an admin of a superuser tenant: the dataplane is the agent tenant's");
        assertNotNull(DataPlaneWsHandler.tenantRefusal(c.newInstance("tenant-a", "bob", "user", false), null), "tenant unknown: fail closed");
        assertNotNull(DataPlaneWsHandler.tenantRefusal(null, "tenant-a"));
    }
}
