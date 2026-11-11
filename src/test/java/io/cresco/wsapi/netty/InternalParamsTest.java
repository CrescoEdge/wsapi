package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import org.apache.activemq.command.ActiveMQBytesMessage;
import org.apache.activemq.command.ActiveMQMapMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The fabric's own stamps (signatures, caller stamps, routing annotations, RPC plumbing) never reach an API client. */
class InternalParamsTest {

    private static final Gson GSON = new Gson();
    private static final Type SMAP = new TypeToken<Map<String, String>>() {}.getType();
    private static final Type OMAP = new TypeToken<Map<String, Object>>() {}.getType();

    /** Every parameter the fabric writes on a message in transit or as it is signed (controller MsgSigning, CallerStamps, MsgRouter, RPC). */
    static final List<String> FABRIC = List.of(
            "cresco_sig", "cresco_sig_by", "cresco_sig_ts", "cresco_sig_cred", "cresco_sig_pending",
            "cresco_sig_caller", "cresco_sig_caller_tenant", "cresco_sig_caller_roles",
            "cresco_caller", "cresco_caller_tenant", "cresco_caller_roles", "cresco_caller_alias",
            "routepath-agent-1", "linkcost-agent-1", "srcroute", "srcroute_dst_plugin", "srcroute-hop-agent-2",
            "callId-region-1-agent-1-plugin-7");

    private static MsgEvent stampedReply() {
        MsgEvent r = new MsgEvent(MsgEvent.Type.EXEC, "r1", "a1", "plugin/2", "r1", "a2", "plugin/7", true, true);
        r.setParam("action", "repolist");
        r.setParam("status_code", "10");
        r.setParam("client_rpc_id", "c0ffee");
        r.setParam("repolist", "{\"plugins\":[]}");
        r.setParam("ttl", "4");
        r.setParam("is_rpc", "true");
        r.setParam("cresco_tenant", "tenant-a");
        for (String k : FABRIC) r.setParam(k, k.equals("cresco_sig_cred") ? "cert:MIIB" + "A".repeat(4000) : "v-" + k);
        return r;
    }

    @Test
    void aReplyReachesTheClientWithoutTheFabricsStamps() {
        Map<String, String> sent = GSON.fromJson(ApiSocketWsHandler.success(stampedReply()), SMAP);
        for (String k : FABRIC) assertFalse(sent.containsKey(k), k + " reached the client: " + sent.keySet());
        assertFalse(sent.containsKey("cresco_tenant"), "the namespace tenant is the fabric's");
        assertFalse(sent.containsKey("is_rpc"), "RPC plumbing");
        assertEquals("repolist", sent.get("action"));
        assertEquals("10", sent.get("status_code"));
        assertEquals("c0ffee", sent.get("client_rpc_id"), "the client's correlation id is echoed as before");
        assertEquals("{\"plugins\":[]}", sent.get("repolist"), "the responder's answer is untouched");
        assertEquals("4", sent.get("ttl"));
        for (String v : sent.values()) assertFalse(v.contains("MIIB"), "no certificate text");
    }

    @Test
    void aFailureEchoCarriesNoStampEither() {
        Map<String, String> payload = new HashMap<>();
        payload.put("action", "x");
        payload.put("client_rpc_id", "c1");
        payload.put("cresco_sig", "forged");
        payload.put("routepath-a", "1");
        Map<String, String> sent = GSON.fromJson(ApiSocketWsHandler.failure(payload, "504", "no reply"), SMAP);
        // gateway_status: the gateway's own mark on its own answers (GatewayStatusTest)
        assertEquals(Set.of("action", "client_rpc_id", "status", "status_desc", "error", "gateway_status"), sent.keySet());
    }

    @Test
    void aClientCannotWriteTheFabricsParameters() throws Exception {
        Map<String, String> payload = new HashMap<>();
        for (String k : FABRIC) payload.put(k, "forged");
        payload.put("action", "listagents");
        payload.put("client_rpc_id", "c2");
        payload.put("cresco_tenant", "tenant-a");
        Authenticator auth = new Authenticator(() -> null, () -> null, () -> false, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        assertNull(ApiSocketWsHandler.vet(payload, ApiEdgeRolesTest.principal("tenant-a", "bob", "user", List.of()), auth));
        assertEquals(Set.of("action", "client_rpc_id", "cresco_tenant"), payload.keySet(),
                "only the client's own parameters, and the tenant it addresses (vetted), are sent");
    }

    @Test
    void aClientSetsNeitherTheHopCountNorTheRpcFlag() throws Exception {
        // the router counts hops in ttl (a negative one never reaches the limit) and the library answers what carries is_rpc
        Map<String, String> payload = new HashMap<>();
        payload.put("action", "listagents");
        payload.put("client_rpc_id", "c3");
        payload.put("ttl", String.valueOf(Integer.MIN_VALUE));
        payload.put("is_rpc", "true");
        Authenticator auth = new Authenticator(() -> null, () -> null, () -> false, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        for (String role : new String[]{"user", "admin"}) {
            Map<String, String> p = new HashMap<>(payload);
            assertNull(ApiSocketWsHandler.vet(p, ApiEdgeRolesTest.principal("tenant-a", "bob", role, List.of()), auth));
            assertEquals(Set.of("action", "client_rpc_id"), p.keySet(), role + ": ttl and is_rpc are the fabric's to write");
        }

        // sent without waiting for a reply (message_info says so): what wsapi hands to the controller carries neither
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        io.cresco.library.plugin.PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<Object[]> sent = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel(new ApiSocketWsHandler(plugin,
                ApiEdgeRolesTest.principal("tenant-a", "bob", "user", List.of()), ReviewApiDataplaneTest.gate(plugin, sent)));
        ch.writeInbound(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(GSON.toJson(Map.of(
                "message_info", Map.of("message_type", "agent_msgevent", "message_event_type", "EXEC", "is_rpc", "false"),
                "message_payload", payload))));
        long until = System.currentTimeMillis() + 5_000;
        while (sent.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10);
        assertEquals(1, sent.size());
        MsgEvent m = (MsgEvent) sent.get(0)[0];
        assertEquals("listagents", m.getParam("action"));
        assertNull(m.getParam("ttl"), "the router starts the hop count");
        assertNull(m.getParam("is_rpc"), "not an RPC: nothing answers it");
        ch.finishAndReleaseAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void envelopePropertiesKeepTheStreamsAndTheSequenceNotTheStamps() throws Exception {
        ActiveMQTextMessage m = new ActiveMQTextMessage();
        m.setText("hello");
        m.setStringProperty("stream_name", "u:bob:mine");
        m.setStringProperty("type", "output");
        m.setIntProperty("dp_bytes", 5);
        m.setLongProperty("cresco_seq", 41L);
        m.setLongProperty("cresco_gap", 2L);
        m.setStringProperty("cresco_caller", "plugin:tenant-a/r1/a1/plugin-3");
        m.setStringProperty("cresco_caller_tenant", "tenant-a");
        m.setStringProperty("cresco_caller_roles", "plugin");
        m.setStringProperty("cresco_caller_alias", "r0");
        m.setStringProperty("cresco_writer", "api:tenant-a/bob");
        m.setStringProperty("cresco_writer_roles", "user");
        m.setStringProperty("cresco_hops", "r1_a1>r1_rc");
        m.setStringProperty("cresco_trace", "1");
        m.setStringProperty("cresco_to", "r1_a1");
        m.setStringProperty("cresco_to_tenant", "tenant-a");
        Map<String, Object> e = GSON.fromJson(DataPlaneFrames.envelopeJson(m), OMAP);
        Map<String, Object> p = (Map<String, Object>) e.get("properties");
        assertEquals(Set.of("stream_name", "type", "dp_bytes", "cresco_seq", "cresco_gap"), p.keySet());
        assertEquals("api:tenant-a/bob", e.get("writer"), "the verified writer is still given, in its own field");
        assertEquals("user", e.get("writer_roles"));
        assertEquals("tenant-a", e.get("writer_tenant"));

        ActiveMQMapMessage map = new ActiveMQMapMessage();
        map.setString("cmd", "execution_log");
        map.setStringProperty("cresco_caller", "plugin:tenant-a/r1/a1/system-executor");
        map.setStringProperty("cresco_caller_roles", "plugin");
        map.setStringProperty("cresco_caller_tenant", "tenant-a");
        Map<String, Object> me = GSON.fromJson(DataPlaneFrames.envelopeJson(map), OMAP);
        assertTrue(((Map<String, Object>) me.get("properties")).isEmpty());
        assertEquals("plugin:tenant-a/r1/a1/system-executor", me.get("writer"));

        ActiveMQBytesMessage bm = new ActiveMQBytesMessage();
        bm.setStringProperty("stream_name", "u:bob:mine");
        bm.setStringProperty("cresco_caller", "plugin:tenant-a/r1/a1/system-filerepo");
        bm.setLongProperty("cresco_seq", 7L);
        byte[] frame = DataPlaneFrames.envelopeBytes(bm, new byte[]{1});
        int n = ByteBuffer.wrap(frame).getInt();
        Map<String, Object> h = GSON.fromJson(new String(frame, 4, n, StandardCharsets.UTF_8), OMAP);
        assertEquals(Set.of("stream_name", "cresco_seq"), ((Map<String, Object>) h.get("properties")).keySet());
        assertEquals("plugin:tenant-a/r1/a1/system-filerepo", h.get("writer"));
    }

    @Test
    void theRulesAreByName() {
        for (String k : FABRIC) {
            assertTrue(InternalParams.internalReply(k), k);
            assertTrue(InternalParams.internalRequest(k), k);
        }
        assertTrue(InternalParams.internalReply("cresco_sig_anything_new"), "a future signature field too");
        assertFalse(InternalParams.internalRequest("cresco_tenant"), "a client addresses a tenant (vetted)");
        assertFalse(InternalParams.internalReply("client_rpc_id"));
        assertFalse(InternalParams.internalReply("status_desc"));
        assertTrue(InternalParams.internalProperty("JMSXGroupID"));
        assertTrue(InternalParams.internalProperty("cresco_anything_new"));
        assertFalse(InternalParams.internalProperty("cresco_seq"));
        assertFalse(InternalParams.internalProperty("transfer_id"));
    }
}
