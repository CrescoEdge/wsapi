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
}
