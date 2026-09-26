package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSocketRpcTimeoutTest {

    @Test
    void timeoutIsClampedAndOptional() {
        assertEquals(0, ApiSocketWsHandler.rpcTimeoutMs(Map.of()));
        assertEquals(0, ApiSocketWsHandler.rpcTimeoutMs(null));
        assertEquals(0, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "soon")));
        assertEquals(1_000, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "5")));
        assertEquals(600_000, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "600000")));
        assertEquals(ApiSocketWsHandler.MAX_RPC_TIMEOUT_MS, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "999999999999")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureCarriesTheRequestParamsBack() {
        // the correlation id must come back, or a correlating client waits out its own timeout
        String r = ApiSocketWsHandler.failure(Map.of("action", "core.publish", "client_rpc_id", "abc123"), "504", "no reply");
        Map<String, String> m = new Gson().fromJson(r, Map.class);
        assertEquals("abc123", m.get("client_rpc_id"));
        assertEquals("504", m.get("status"));
        assertEquals("core.publish", m.get("action"));
        assertTrue(m.get("status_desc").contains("no reply"));
        assertEquals("500", new Gson().fromJson(ApiSocketWsHandler.failure(null, "500", "x"), Map.class).get("status"));
    }
}
