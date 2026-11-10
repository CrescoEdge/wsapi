package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.websockets.StreamInfo;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.jms.Message;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Role separation at the API edge: dataplane stream ownership and grants, reserved properties, the writer stamp, the log
 * stream for users, a user's destinations, the address a failure counts against, and browser Origins.
 */
class ApiEdgeRolesTest {

    static Authenticator.Principal principal(String tenant, String user, String role, List<String> grants) throws Exception {
        java.lang.reflect.Constructor<Authenticator.Principal> c = Authenticator.Principal.class
                .getDeclaredConstructor(String.class, String.class, String.class, boolean.class, List.class);
        c.setAccessible(true);
        return c.newInstance(tenant, user, role, false, grants);
    }

    static StreamInfo json(String key, String id, String typeKey, String in) {
        StreamInfo si = new StreamInfo("t", key, id);
        si.setIoTypeKey(typeKey);
        si.setInputId(in);
        return si;
    }

    @Test
    void aUserUsesOnlyItsOwnAndGrantedStreams() throws Exception {
        Authenticator.Principal bob = principal("tenant-a", "bob", "user", List.of("shared-job-7", "team/*"));
        assertNull(DataPlaneWsHandler.userStreamRefusal(json("stream_name", "u:bob:mine", "type", "output"), bob));
        assertNull(DataPlaneWsHandler.userStreamRefusal(json("stream_name", "shared-job-7", "type", "in"), bob));
        assertNull(DataPlaneWsHandler.userStreamRefusal(json("stream_name", "team/x", "type", "in"), bob));
        assertNull(DataPlaneWsHandler.userStreamRefusal(new StreamInfo("t", "stream_name='u:bob:mine' AND type='output'"), bob));
        assertNull(DataPlaneWsHandler.userStreamRefusal(new StreamInfo("t", "type='error' AND stream_name='team/build'"), bob));
        for (StreamInfo no : new StreamInfo[]{
                json("stream_name", "u:alice:x", "type", "in"),                 // another user's
                json("stream_name", "u:bob", "type", "in"),                     // not under u:bob:
                json("stream_name", "admin-job", "type", "in"),
                json("job", "u:bob:x", "type", "in"),                           // not named by stream_name
                json("stream_name", "shared-job-7", "type", "output"),          // posing as a process's output
                new StreamInfo("t", "type='output'"),
                new StreamInfo("t", "seq_num='0'"),
                new StreamInfo("t", "stream_name='admin-job'"),
                new StreamInfo("t", "stream_name='u:alice:x'")}) {
            assertNotNull(DataPlaneWsHandler.userStreamRefusal(no, bob), String.valueOf(no.getIdentId() != null ? no.getIdentId() : no.getStream_query()));
        }
        Authenticator.Principal admin = principal("tenant-a", "alice", "admin", List.of());
        assertNull(DataPlaneWsHandler.userStreamRefusal(new StreamInfo("t", "type='output'"), admin), "admins are not limited by ownership");
    }

    @Test
    void nobodyWritesAProcesssStdinOnTheDataplaneAnAdminIncluded() throws Exception {
        for (String t : new String[]{"input", "input-0123456789abcdef"}) {
            String why = DataPlaneWsHandler.stdinRefusal(json("stream_name", "runner-1", "type", t));
            assertNotNull(why, t);
            assertTrue(why.contains("write_stdin"), why);
        }
        assertNull(DataPlaneWsHandler.stdinRefusal(json("stream_name", "runner-1", "type", "in")));
        assertNull(DataPlaneWsHandler.stdinRefusal(json("stream_name", "runner-1", "type", "inputs")));
        assertNull(DataPlaneWsHandler.stdinRefusal(json("stream_name", "runner-1", "kind", "input")), "only the executor's type property is stdin");
        for (String role : new String[]{"admin", "user"}) {
            ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
            PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
            String user = role.equals("admin") ? "root" : "bob";
            EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, principal("tenant-a", user, role, List.of()),
                    ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
            ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(Map.of("ident_key", "stream_name", "ident_id", "u:" + user + ":runner",
                    "io_type_key", "type", "input_id", "input-0123456789abcdef"))));
            Map<String, String> r = ReviewApiDataplaneTest.reply(ch);
            assertEquals("9", r.get("status_code"), role);
            assertTrue(r.get("status_desc").contains("write_stdin"), r.toString());
            ch.writeInbound(new TextWebSocketFrame("typed into the process"));
            assertEquals(0, rec.sent.size(), role + ": nothing was published");
        }
    }

    @Test
    void reservedPropertyNamesAreRefusedForEveryone() {
        for (String name : new String[]{"cresco_caller", "CRESCO_caller_roles", "cresco_tenant", "cresco_writer", "transfer_id",
                "transfer_end", "seq_num", "offset", "sha384", "dp_bytes", "region_id", "session_id", "loglevel"}) {
            assertTrue(StreamSelectors.reservedName(name), name);
            assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(json(name, "v", "type", "in")), name);
            assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(json("stream_name", "v", name, "in")), name);
        }
        assertFalse(StreamSelectors.reservedName("stream_name"));
        assertFalse(StreamSelectors.reservedName("type"));
    }

    @Test
    void everythingAClientPublishesCarriesItsVerifiedWriter() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        Authenticator.Principal bob = principal("tenant-a", "bob", "user", List.of());
        EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, bob, ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(Map.of("ident_key", "stream_name", "ident_id", "u:bob:s",
                "io_type_key", "type", "input_id", "in"))));
        assertEquals("10", ReviewApiDataplaneTest.reply(ch).get("status_code"));
        ch.writeInbound(new TextWebSocketFrame("hello"));
        assertEquals(1, rec.sent.size());
        Message m = rec.sent.get(0);
        assertEquals("api:tenant-a/bob", m.getStringProperty("cresco_writer"));
        assertEquals("user", m.getStringProperty("cresco_writer_roles"));
        assertEquals("u:bob:s", m.getStringProperty("stream_name"));
        // the subscription bob made: his stream only
        assertEquals("stream_name='u:bob:s'", rec.selectors.get(0));
    }

    @Test
    void aRefusedHandshakeLeavesTheStreamUnactivatedAndTheThirdClosesIt() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, principal("tenant-a", "bob", "user", List.of()),
                ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        ch.writeInbound(new TextWebSocketFrame("type='output'"));
        assertEquals("9", ReviewApiDataplaneTest.reply(ch).get("status_code"));
        ch.writeInbound(new TextWebSocketFrame("seq_num='0'"));
        assertEquals("9", ReviewApiDataplaneTest.reply(ch).get("status_code"));
        assertTrue(ch.isOpen());
        ch.writeInbound(new TextWebSocketFrame("stream_name='admin'"));
        assertEquals("9", ReviewApiDataplaneTest.reply(ch).get("status_code"));
        assertFalse(ch.isOpen(), "closed after " + DataPlaneWsHandler.MAX_REFUSED_HANDSHAKES + " refusals");
        assertTrue(rec.selectors.isEmpty() && rec.sent.isEmpty());
    }

    @Test
    void aUserCannotConfigureTheLogStreamAndSeesOnlyWarningsAnAdminCan() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<Object[]> rpcs = Collections.synchronizedList(new ArrayList<>());
        EmbeddedChannel user = new EmbeddedChannel(new LogStreamerWsHandler(plugin, principal("tenant-a", "bob", "user", List.of()),
                ReviewApiDataplaneTest.gate(plugin, rpcs)));
        user.pipeline().fireUserEventTriggered(new io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete("/api/logstreamer",
                new io.netty.handler.codec.http.DefaultHttpHeaders(), null));
        user.writeInbound(new TextWebSocketFrame("region-1,agent-1,Info,io.cresco"));
        assertTrue(rpcs.isEmpty(), "no setlogdp / setloglevel for a user");
        String sel = rec.selectors.get(0);
        assertTrue(sel.contains("loglevel = 'Warn' OR loglevel = 'Error'"), sel);
        EmbeddedChannel admin = new EmbeddedChannel(new LogStreamerWsHandler(plugin, principal("tenant-a", "alice", "admin", List.of()),
                ReviewApiDataplaneTest.gate(plugin, rpcs)));
        admin.writeInbound(new TextWebSocketFrame("region-1,agent-1,Info,io.cresco"));
        // configuration runs off the event loop (the gate's LogStreamPools.config)
        long until = System.currentTimeMillis() + 5_000;
        while (rpcs.size() < 2 && System.currentTimeMillis() < until) Thread.sleep(20);
        List<String> actions = new ArrayList<>();
        for (Object[] r : rpcs) actions.add(((MsgEvent) r[0]).getParam("action"));
        assertEquals(List.of("setlogdp", "setloglevel"), actions);
    }

    @Test
    void aUserAddressesOnlyItsOwnRegion() throws Exception {
        Authenticator.Principal bob = principal("tenant-a", "bob", "user", List.of());
        // messages as wsapi builds them on an agent of region-1 (what is judged), from a client's message_info
        ApiSocketWsHandler wsapi = new ApiSocketWsHandler(ReviewApiDataplaneTest.plugin(new ReviewApiDataplaneTest.Recorder()), bob, null);
        java.util.function.Function<Map<String, String>, MsgEvent> built = mi -> {
            Map<String, String> info = new HashMap<>(mi);
            info.put("message_event_type", "EXEC");
            return wsapi.buildMsgEvent(Map.of("message_info", info, "message_payload", Map.of("action", "x")));
        };
        String r1 = "region-1";
        assertNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_agent_msgevent", "dst_region", r1, "dst_agent", "a2")), bob, r1, r1));
        assertNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_controller_msgevent")), bob, r1, r1));
        assertNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "plugin_msgevent", "dst_plugin", "plugin/1")), bob, r1, "rg"));
        assertNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "regional_controller_msgevent")), bob, r1, "rg"));
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_agent_msgevent", "dst_region", "r2", "dst_agent", "a")), bob, r1, r1));
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_controller_msgevent")), bob, r1, "rg"), "the global is in another region");
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_controller_msgevent", "region_id", "r9", "agent_id", "x")), bob, r1, r1));
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_controller_msgevent", "region_id", r1)), bob, r1, "rg"),
                "naming its own region does not make a message for the global controller local");
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_controller_msgevent", "region_id", r1, "agent_id", "agent-1")), bob, r1, "rg"),
                "nor does naming its own agent: the message is flagged global");
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_controller_msgevent")), bob, r1, null), "the global's region is not known");
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_plugin_msgevent")), bob, r1, r1), "no region named");
        assertNotNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "regional_controller_msgevent", "region_id", "r9", "agent_id", "x")), bob, r1, r1));
        assertNotNull(ApiSocketWsHandler.destinationRefusal(null, bob, r1, r1), "nothing was built");
        assertNull(ApiSocketWsHandler.destinationRefusal(built.apply(Map.of("message_type", "global_agent_msgevent", "dst_region", "r2", "dst_agent", "a")),
                principal("tenant-a", "alice", "admin", List.of()), r1, r1), "admins are not limited here");
    }

    @Test
    void aUserDoesNotReachAGlobalControllerInAnotherRegionByNamingItsOwnRegion() throws Exception {
        // this agent is in region-1, the global controller in region-g
        Map<String, Object> config = new HashMap<>();
        config.put("pluginID", "plugin/9");
        PluginBuilder plugin = io.cresco.wsapi.TestSupport.pluginBuilder(java.nio.file.Files.createTempDirectory("wsapi-edge"), config,
                Collections.synchronizedList(new ArrayList<>()), null, "tenant-a", "region-g");
        List<Object[]> sent = Collections.synchronizedList(new ArrayList<>());
        EmbeddedChannel ch = new EmbeddedChannel(new ApiSocketWsHandler(plugin, principal("tenant-a", "bob", "user", List.of()),
                ReviewApiDataplaneTest.gate(plugin, sent)));
        // a message for the global controller, its region_id naming this agent's own region
        for (Map<String, String> mi : List.of(
                Map.of("message_type", "global_controller_msgevent", "message_event_type", "EXEC", "is_rpc", "true", "region_id", "region-1"),
                Map.of("message_type", "global_controller_msgevent", "message_event_type", "EXEC", "is_rpc", "true", "region_id", "region-1",
                        "agent_id", "agent-1"),
                Map.of("message_type", "global_controller_msgevent", "message_event_type", "EXEC", "is_rpc", "true"))) {
            ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(Map.of("message_info", mi, "message_payload", Map.of("action", "listagents")))));
            Object reply = null;
            long until = System.currentTimeMillis() + 5_000;
            while ((reply = ch.readOutbound()) == null && System.currentTimeMillis() < until) Thread.sleep(10);
            assertNotNull(reply, "an RPC is answered");
            Map<?, ?> r = new Gson().fromJson(((TextWebSocketFrame) reply).text(), Map.class);
            assertEquals("403", r.get("status"), mi + " reached the global controller in region-g: " + r);
            assertTrue(sent.isEmpty(), "nothing was sent");
        }
        // its own region's controller, and its own agent, as before
        ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(Map.of("message_info", Map.of("message_type", "regional_controller_msgevent",
                "message_event_type", "EXEC", "is_rpc", "true"), "message_payload", Map.of("action", "listagents")))));
        long until = System.currentTimeMillis() + 5_000;
        while (sent.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10);
        assertEquals(1, sent.size(), "a message to its own region is sent");
        MsgEvent m = (MsgEvent) sent.get(0)[0];
        assertTrue(m.isRegional() && !m.isGlobal() && "region-1".equals(m.getDstRegion()));
        ch.finishAndReleaseAll();
    }

    static String source(String ip) throws Exception {
        return WsRouter.sourceOf(new InetSocketAddress(InetAddress.getByName(ip), 1));
    }

    @Test
    void failuresCountPerIpv4AddressAndPerIpv6Slash64() throws Exception {
        assertEquals("2001:db8:aa:bb::/64 2001:db8:aa:bb:0:0:0:1", source("2001:db8:aa:bb::1"), "the bucket, then the exact address");
        assertEquals("2001:db8:aa:bb::/64", AuthRateLimiter.bucket(source("2001:db8:aa:bb:ffff:1:2:3")));
        assertNotEquals(AuthRateLimiter.bucket(source("2001:db8:aa:bb::1")), AuthRateLimiter.bucket(source("2001:db8:aa:bc::1")));
        assertEquals("192.0.2.7", source("192.0.2.7"));
        assertEquals("192.0.2.7", source("::ffff:192.0.2.7"), "an IPv4-mapped address is its IPv4 address");
        assertEquals("192.0.2.7", source("64:ff9b::c000:207"), "NAT64 (64:ff9b::/96): the IPv4 client, not a /64 of all of them");
        assertEquals("198.51.100.9", source("64:ff9b:1::c633:6409"), "local-use NAT64 (64:ff9b:1::/96)");
        assertEquals("2001:db8:aa:bb::/64", AuthRateLimiter.bucket("2001:db8:aa:bb::99"), "the limiter aggregates a bare address too");
        try {
            io.cresco.wsapi.auth.Sources.ipv6PrefixBits(128);
            assertEquals("2001:db8:aa:bb:0:0:0:1", source("2001:db8:aa:bb::1"), "a shared /64: count each address");
            io.cresco.wsapi.auth.Sources.ipv6PrefixBits(56);
            assertEquals("2001:db8:aa:0:0:0:0:0/56 2001:db8:aa:bb:0:0:0:1", source("2001:db8:aa:bb::1"));
        } finally {
            io.cresco.wsapi.auth.Sources.ipv6PrefixBits(64);
        }
    }

    @Test
    void browserOriginsAreRefusedUnlessWsapisOwnOrAllowlisted() {
        WsRouter.Gate g = new WsRouter.Gate(null, null, 1, 1, null).limits(2, 64, 256, List.of("https://console.example.org/"));
        assertTrue(g.originAllowed(null, "agent:8282"), "no Origin: not a browser");
        assertTrue(g.originAllowed("https://agent:8282", "agent:8282"), "wsapi's own origin (older Java clients send it)");
        assertTrue(g.originAllowed("https://AGENT", "agent:443"));
        assertTrue(g.originAllowed("https://console.example.org", "agent:8282"), "allowlisted");
        assertFalse(g.originAllowed("https://evil.example", "agent:8282"));
        assertFalse(g.originAllowed("http://agent:8282", "agent:8282"), "not over TLS");
        assertFalse(g.originAllowed("https://agent:8283", "agent:8282"), "another port is another origin");
        assertFalse(g.originAllowed("null", "agent:8282"));
        g.shutdown();
    }

    @Test
    void passwordChecksPendingPerSourceAndConnectionsPerCallerAreBounded() throws Exception {
        WsRouter.Gate g = new WsRouter.Gate(null, null, 1, 1, null).limits(2, 3, 5, List.of());
        assertTrue(g.reservePending("s"));
        assertTrue(g.reservePending("s"));
        assertFalse(g.reservePending("s"), "a third pending check from one source waits its turn");
        assertTrue(g.reservePending("other"), "other sources are not affected");
        g.releasePending("s");
        assertTrue(g.reservePending("s"));
        Authenticator.Principal bob = principal("tenant-a", "bob", "user", List.of());
        for (int i = 0; i < 3; i++) assertTrue(g.openConnection(bob));
        assertFalse(g.openConnection(bob));
        assertEquals(3, g.connections(bob));
        g.closeConnection(bob);
        assertTrue(g.openConnection(bob));
        Authenticator.Principal alice = principal("tenant-a", "alice", "admin", List.of());
        for (int i = 0; i < 5; i++) assertTrue(g.openConnection(alice));
        assertFalse(g.openConnection(alice));
        g.shutdown();
    }
}
