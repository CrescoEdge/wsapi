package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An answer wsapi itself gives carries gateway_status; a reply from the fabric never does. Before, a 403 from the
 * gateway and a 403 from the node that was asked looked the same to a client (the same parameter names).
 */
class GatewayStatusTest {

    private static Map<String, String> parse(String json) {
        return new Gson().fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
    }

    @Test
    void everyAnswerOfTheGatewayNamesItsStatusAsTheGateways() {
        for (String code : new String[]{"400", "403", "429", "500", "503", "504"}) {
            Map<String, String> a = parse(ApiSocketWsHandler.failure(Map.of("action", "x", "client_rpc_id", "id-1"), code, "why"));
            assertEquals(code, a.get("status"));
            assertEquals(code, a.get("gateway_status"));
            assertEquals("id-1", a.get("client_rpc_id"), "still correlated with its request");
        }
        assertEquals("400", parse(ApiSocketWsHandler.failure(null, "400", "unreadable")).get("gateway_status"));
    }

    @Test
    void aRequestCannotChooseWhatTheGatewaysAnswerSays() {
        Map<String, String> payload = new HashMap<>();
        payload.put("action", "x");
        payload.put("gateway_status", "200");
        assertEquals("403", parse(ApiSocketWsHandler.failure(payload, "403", "unauthorized: no")).get("gateway_status"));
    }

    @Test
    void aReplyFromTheFabricNeverCarriesIt() {
        Map<String, String> fromANode = new HashMap<>();
        fromANode.put("status", "403");
        fromANode.put("status_desc", "unauthorized: the node says no");
        fromANode.put("gateway_status", "403");                 // a node (or the request it echoes) wrote it
        Map<String, String> toClient = InternalParams.reply(fromANode);
        assertEquals("403", toClient.get("status"));
        assertNull(toClient.get("gateway_status"), "only the gateway's own answers carry it");
        assertTrue(InternalParams.internalReply("gateway_status"));
        assertFalse(InternalParams.internalRequest("gateway_status"), "a request may carry the name; it means nothing there");
    }
}
