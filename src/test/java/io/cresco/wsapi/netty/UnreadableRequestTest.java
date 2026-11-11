package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.cresco.library.plugin.PluginBuilder;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A request the API socket cannot read is answered 400 (it used to be dropped: a caller that waited for an answer waited
 * out its timeout), counted, and the connection is closed at the third.
 */
class UnreadableRequestTest {

    private static final Gson GSON = new Gson();

    private static EmbeddedChannel channel() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<Object[]> sent = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        return new EmbeddedChannel(new ApiSocketWsHandler(plugin,
                ApiEdgeRolesTest.principal("tenant-a", "bob", "user", List.of()), ReviewApiDataplaneTest.gate(plugin, sent)));
    }

    private static Map<String, String> answer(EmbeddedChannel ch) {
        TextWebSocketFrame f = ch.readOutbound();
        assertNotNull(f, "an answer");
        try {
            return GSON.fromJson(f.text(), new TypeToken<Map<String, String>>() { }.getType());
        } finally {
            f.release();
        }
    }

    @Test
    void aFrameThatIsNotJsonIsAnswered400AndCounted() throws Exception {
        EmbeddedChannel ch = channel();
        long before = ApiSocketWsHandler.UNREADABLE_REQUESTS.get();
        ch.writeInbound(new TextWebSocketFrame("{\"message_info\": {\"is_rpc\": \"true\", secret-looking text"));
        Map<String, String> a = answer(ch);
        assertEquals("400", a.get("status"));
        assertTrue(a.get("status_desc").startsWith("unreadable request"), a.toString());
        assertFalse(a.toString().contains("secret-looking"), "nothing of the request comes back");
        assertEquals(before + 1, ApiSocketWsHandler.UNREADABLE_REQUESTS.get());
        assertTrue(ch.isOpen());
        ch.finishAndReleaseAll();
    }

    @Test
    void jsonOfAnotherShapeIsAnswered400() throws Exception {
        for (String frame : new String[]{"[1, 2, 3]", "\"text\"", "{\"message_info\": \"not a map\"}", "null", ""}) {
            EmbeddedChannel ch = channel();
            ch.writeInbound(new TextWebSocketFrame(frame));
            assertEquals("400", answer(ch).get("status"), frame);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void theThirdUnreadableRequestClosesTheConnection() throws Exception {
        EmbeddedChannel ch = channel();
        for (int i = 1; i <= ApiSocketWsHandler.MAX_UNREADABLE_REQUESTS; i++) {
            assertTrue(ch.isOpen(), "open before request " + i);
            ch.writeInbound(new TextWebSocketFrame("not json " + i));
            assertEquals("400", answer(ch).get("status"));
        }
        assertFalse(ch.isOpen(), "closed after " + ApiSocketWsHandler.MAX_UNREADABLE_REQUESTS + " unreadable requests");
        ch.finishAndReleaseAll();
    }

    @Test
    void aReadableRequestIsNotCountedAsUnreadable() throws Exception {
        EmbeddedChannel ch = channel();
        long before = ApiSocketWsHandler.UNREADABLE_REQUESTS.get();
        ch.writeInbound(new TextWebSocketFrame(GSON.toJson(Map.of(
                "message_info", Map.of("message_type", "no_such_type", "message_event_type", "EXEC", "is_rpc", "true"),
                "message_payload", Map.of("action", "listagents")))));
        Map<String, String> a = answer(ch);
        assertEquals("400", a.get("status"));
        assertEquals("unknown or malformed message_info", a.get("status_desc"));
        assertEquals(before, ApiSocketWsHandler.UNREADABLE_REQUESTS.get());
        ch.finishAndReleaseAll();
    }
}
