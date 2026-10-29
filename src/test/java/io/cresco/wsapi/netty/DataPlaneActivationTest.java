package io.cresco.wsapi.netty;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The activation reply a dataplane client gets, in particular when the broker refuses its subscription. */
class DataPlaneActivationTest {

    @Test
    void aRefusedSubscriptionIsATypedReplyWithTheBrokersReason() {
        Map<String, String> r = DataPlaneWsHandler.activationReply(() -> {
            throw new SecurityException("Cresco stream policy: subscription to 'global.event' denied (selector asks for the stream of recipient rgA_a1)");
        });
        assertEquals(DataPlaneWsHandler.STATUS_SUBSCRIPTION_REFUSED, r.get("status_code"));
        assertEquals("refused", r.get("subscription"));
        assertTrue(r.get("status_desc").contains("rgA_a1"), r.get("status_desc"));
    }

    @Test
    void otherOutcomesKeepTheirCodes() {
        assertEquals("10", DataPlaneWsHandler.activationReply(() -> true).get("status_code"));
        assertEquals("9", DataPlaneWsHandler.activationReply(() -> false).get("status_code"));
        assertEquals("90", DataPlaneWsHandler.activationReply(() -> { throw new IllegalStateException("boom"); }).get("status_code"));
    }

    @Test
    void aSubscriptionRefusedOnReconnectClosesTheStreamWithTheReason() {
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        DataPlaneWsHandler.closeRefused(ch, "Cresco stream policy: subscription to 'global.event' denied (" + "x".repeat(300) + ")");
        Object out = ch.readOutbound();
        assertTrue(out instanceof io.netty.handler.codec.http.websocketx.CloseWebSocketFrame, String.valueOf(out));
        io.netty.handler.codec.http.websocketx.CloseWebSocketFrame close = (io.netty.handler.codec.http.websocketx.CloseWebSocketFrame) out;
        assertEquals(1008, close.statusCode());
        assertTrue(close.reasonText().startsWith("Subscription refused: Cresco stream policy"));
        assertTrue(close.reasonText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 123);
        assertTrue(!ch.isOpen(), "the stream was not closed");
        close.release();
    }
}
