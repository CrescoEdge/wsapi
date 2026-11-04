package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.apache.activemq.command.ActiveMQBytesMessage;
import org.apache.activemq.command.ActiveMQMapMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Round 2: every routing property a client sets is checked; envelope frames carry the verified writer. */
class DataPlaneRoutingAndEnvelopeTest {

    static final Gson GSON = new Gson();

    static Map<String, String> handshake(String user, String role, Map<String, String> hs) throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", user, role),
                ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        ch.writeInbound(new TextWebSocketFrame(GSON.toJson(hs)));
        return ReviewApiDataplaneTest.reply(ch);
    }

    @Test
    void nobodyStampsTheStreamsOwnPropertyTwiceAndAUserSetsOnlyType() throws Exception {
        Map<String, String> twice = Map.of("ident_key", "stream_name", "ident_id", "job-1", "io_type_key", "stream_name", "input_id", "job-2");
        Map<String, String> r = handshake("root", "admin", twice);
        assertEquals("9", r.get("status_code"), "an admin too: " + r);
        assertTrue(r.get("status_desc").contains("io_type_key may not name"), r.toString());
        assertEquals("9", handshake("root", "admin", Map.of("ident_key", "job", "ident_id", "a", "io_type_key", "JOB", "input_id", "b"))
                .get("status_code"), "property names are case-insensitive in selectors");

        Map<String, String> other = Map.of("ident_key", "stream_name", "ident_id", "u:bob:mine", "io_type_key", "gk_stream", "input_id", "gk-data-1");
        r = handshake("bob", "user", other);
        assertEquals("9", r.get("status_code"), "a user routing its messages into another selector's stream: " + r);
        assertTrue(r.get("status_desc").contains("may set only type"), r.toString());
        assertEquals("10", handshake("bob", "user", Map.of("ident_key", "stream_name", "ident_id", "u:bob:mine", "io_type_key", "type",
                "input_id", "data")).get("status_code"));
        assertEquals("10", handshake("root", "admin", other).get("status_code"), "admins may route by other properties");
    }

    @Test
    void anEnvelopeHandshakeActivates() throws Exception {
        Map<String, String> hs = new HashMap<>(Map.of("ident_key", "stream_name", "ident_id", "u:bob:mine", "envelope", "true"));
        assertEquals("10", handshake("bob", "user", hs).get("status_code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void envelopeFramesNameTheVerifiedWriter() throws Exception {
        // a user's text that looks like an exit event, as wsapi published it and the controller kept its writer
        ActiveMQTextMessage forged = new ActiveMQTextMessage();
        forged.setText("{\"cmd\":\"execution_log\",\"log\":\"Exit Code: 0\"}");
        forged.setStringProperty("stream_name", "job-1");
        forged.setStringProperty(DataPlaneWsHandler.WRITER, "api:tenant-a/bob");
        forged.setStringProperty(DataPlaneWsHandler.WRITER_ROLES, "user");
        forged.setStringProperty("cresco_caller", "plugin:region-1/agent-1/system-wsapi");
        Map<String, Object> t = GSON.fromJson(DataPlaneFrames.envelopeJson(forged), Map.class);
        assertEquals("text", t.get("kind"));
        assertEquals("api:tenant-a/bob", t.get("writer"));
        assertEquals("user", t.get("writer_roles"));
        assertEquals("tenant-a", t.get("writer_tenant"));
        assertEquals("{\"cmd\":\"execution_log\",\"log\":\"Exit Code: 0\"}", t.get("text"));
        assertEquals("job-1", ((Map<String, Object>) t.get("properties")).get("stream_name"));

        // the executor's real one: a map event, stamped by the controller with the executor plugin as its caller
        ActiveMQMapMessage real = new ActiveMQMapMessage();
        real.setString("cmd", "execution_log");
        real.setString("log", "Exit Code: 0");
        real.setStringProperty("stream_name", "job-1");
        real.setStringProperty("cresco_caller", "plugin:region-1/agent-1/system-executor");
        real.setStringProperty("cresco_caller_tenant", "tenant-a");
        real.setStringProperty("cresco_caller_roles", "infra");
        Map<String, Object> m = GSON.fromJson(DataPlaneFrames.envelopeJson(real), Map.class);
        assertEquals("map", m.get("kind"));
        assertEquals("plugin:region-1/agent-1/system-executor", m.get("writer"));
        assertEquals("infra", m.get("writer_roles"));
        assertEquals("tenant-a", m.get("writer_tenant"));
        assertEquals("execution_log", ((Map<String, Object>) m.get("map")).get("cmd"));

        // data: a binary frame, header length, JSON header, payload as published
        ActiveMQBytesMessage bm = new ActiveMQBytesMessage();
        bm.setStringProperty("stream_name", "job-1");
        bm.setStringProperty("cresco_caller", "plugin:region-1/agent-1/system-filerepo");
        byte[] frame = DataPlaneFrames.envelopeBytes(bm, new byte[]{1, 2, 3});
        int n = ByteBuffer.wrap(frame).getInt();
        Map<String, Object> h = GSON.fromJson(new String(frame, 4, n, StandardCharsets.UTF_8), Map.class);
        assertEquals("bytes", h.get("kind"));
        assertEquals("plugin:region-1/agent-1/system-filerepo", h.get("writer"));
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOfRange(frame, 4 + n, frame.length));

        ActiveMQTextMessage unstamped = new ActiveMQTextMessage();
        unstamped.setText("x");
        assertNull(GSON.fromJson(DataPlaneFrames.envelopeJson(unstamped), Map.class).get("writer"), "no stamp: no writer claimed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainMapFramesNameTheirWriterAndAUserCannotForgeIt() throws Exception {
        ActiveMQMapMessage real = new ActiveMQMapMessage();
        real.setString("cmd", "execution_log");
        real.setStringProperty("cresco_caller", "plugin:tenant-a/region-1/agent-1/plugin-3");
        real.setStringProperty("cresco_caller_roles", "plugin");
        Map<String, Object> m = GSON.fromJson(DataPlaneFrames.withWriter(DataPlaneFrames.mapJson(real), real), Map.class);
        assertEquals("execution_log", m.get("cmd"), "the event as before");
        assertEquals("plugin:tenant-a/region-1/agent-1/plugin-3", m.get("cresco_writer"));
        assertEquals("plugin", m.get("cresco_writer_roles"));

        assertTrue(DataPlaneFrames.claimsWriter("{\"cmd\":\"execution_log\",\"cresco_writer\":\"plugin:x\"}"));
        assertTrue(DataPlaneFrames.claimsWriter("  {\"cresco_writer_roles\":\"plugin\"}"));
        assertFalse(DataPlaneFrames.claimsWriter("{\"cmd\":\"execution_log\"}"));
        assertFalse(DataPlaneFrames.claimsWriter("the word cresco_writer in a line of text"));
        assertFalse(DataPlaneFrames.claimsWriter("{\"note\":\"cresco_writer\"}"), "only a top-level key");

        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "bob", "user"),
                ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        ch.writeInbound(new TextWebSocketFrame(GSON.toJson(Map.of("ident_key", "stream_name", "ident_id", "u:bob:x", "io_type_key", "type", "input_id", "data"))));
        assertEquals("10", ReviewApiDataplaneTest.reply(ch).get("status_code"));
        ch.writeInbound(new TextWebSocketFrame("{\"cmd\":\"execution_log\",\"cresco_writer\":\"plugin:tenant-a/r/a/plugin-3\"}"));
        assertEquals(0, rec.sent.size(), "the forged writer was not published");
        Object close = ch.readOutbound();
        assertTrue(close instanceof io.netty.handler.codec.http.websocketx.CloseWebSocketFrame, String.valueOf(close));
        assertEquals(1008, ((io.netty.handler.codec.http.websocketx.CloseWebSocketFrame) close).statusCode());
    }

    @Test
    void onItsOwnStreamAUserMayWriteAnyTypeOnAGrantedOneNotTheExecutors() throws Exception {
        java.lang.reflect.Constructor<Authenticator.Principal> c = Authenticator.Principal.class.getDeclaredConstructor(
                String.class, String.class, String.class, boolean.class, List.class);
        c.setAccessible(true);
        Authenticator.Principal bob = c.newInstance("tenant-a", "bob", "user", false, List.of("job-7*"));
        for (String type : new String[]{"output", "error", "event"}) {
            io.cresco.wsapi.websockets.StreamInfo own = new io.cresco.wsapi.websockets.StreamInfo("t", "stream_name", "u:bob:x");
            own.setIoTypeKey("type");
            own.setInputId(type);
            assertNull(DataPlaneWsHandler.userStreamRefusal(own, bob), type);
            io.cresco.wsapi.websockets.StreamInfo granted = new io.cresco.wsapi.websockets.StreamInfo("t", "stream_name", "job-7");
            granted.setIoTypeKey("type");
            granted.setInputId(type);
            assertNotNull(DataPlaneWsHandler.userStreamRefusal(granted, bob), type);
        }
        io.cresco.wsapi.websockets.StreamInfo note = new io.cresco.wsapi.websockets.StreamInfo("t", "stream_name", "job-7");
        note.setIoTypeKey("type");
        note.setInputId("note");
        assertNull(DataPlaneWsHandler.userStreamRefusal(note, bob), "other types on a granted stream");
    }

    @Test
    void aClosedLogStreamTurnsLoggingOffAndEachAdminHoldsAFairShareOfThePool() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<Object[]> rpcs = java.util.Collections.synchronizedList(new ArrayList<>());
        WsRouter.Gate gate = ReviewApiDataplaneTest.gate(plugin, rpcs);
        EmbeddedChannel ch = new EmbeddedChannel(new LogStreamerWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "alice", "admin"), gate));
        ch.writeInbound(new TextWebSocketFrame("region-1,agent-1,Info,io.cresco"));
        long until = System.currentTimeMillis() + 5_000;
        while (rpcs.size() < 2 && System.currentTimeMillis() < until) Thread.sleep(20);
        Thread.sleep(100);
        ch.close();                                                   // configured, then closed
        until = System.currentTimeMillis() + 5_000;
        while (rpcs.size() < 3 && System.currentTimeMillis() < until) Thread.sleep(20);
        MsgEvent off = (MsgEvent) rpcs.get(rpcs.size() - 1)[0];
        assertEquals("setlogdp", off.getParam("action"));
        assertEquals("false", off.getParam("setlogdp"));

        // one admin, many streams, all waiting on a slow agent: at most its share of the pool, the rest told to retry
        CountDownLatch release = new CountDownLatch(1);
        ApiGateway slow = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                msg.setParam("status_code", "7");
                return msg;
            }
        };
        Authenticator auth = new Authenticator(CredentialStore::empty, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        WsRouter.Gate g2 = new WsRouter.Gate(auth, slow, 1, 4, plugin.getLogger("t", CLogger.Level.Info));
        int busy = 0;
        try {
            for (int i = 0; i < 5; i++) {
                EmbeddedChannel s = new EmbeddedChannel(new LogStreamerWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "carl", "admin"), g2));
                s.writeInbound(new TextWebSocketFrame("region-1,agent-" + i + ",Info,io.cresco"));
                Object o = s.readOutbound();
                if (o instanceof TextWebSocketFrame && ((TextWebSocketFrame) o).text().startsWith("busy:")) busy++;
            }
        } finally {
            release.countDown();
        }
        assertEquals(5 - LogStreamerWsHandler.POOL_SHARE_PER_ADMIN, busy);
    }

    @Test
    void theServiceKeysLimitsCountPerSourceHost() throws Exception {
        String strong = "0123456789abcdef".repeat(3);              // test-only, 48 hex digits
        Authenticator a = new Authenticator(CredentialStore::empty, () -> strong, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        Authenticator.Principal h1 = a.authenticate(null, strong, "192.0.2.10").principal;
        Authenticator.Principal h2 = a.authenticate(null, strong, "2001:db8:1:2::/64 2001:db8:1:2:0:0:0:5").principal;
        assertNotEquals(h1.key(), h2.key(), "two hosts sharing the key do not share one connection and RPC budget");
        assertEquals("tenant-a/service-key@192.0.2.10", h1.key());
        assertEquals("tenant-a/service-key@2001:db8:1:2::/64", h2.key());
        assertEquals("api:tenant-a/service-key", h1.caller(), "the caller the fabric sees is unchanged");
        a.serviceKeyLimitsPerSource(false);
        assertEquals("tenant-a/service-key", a.authenticate(null, strong, "192.0.2.10").principal.key());
    }

    @Test
    void aSecondLogConfigurationWhileOneIsRunningIsToldToRetry() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        CountDownLatch release = new CountDownLatch(1);
        List<String> actions = new ArrayList<>();
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                actions.add(msg.getParam("action") + "@" + timeoutMs);
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                msg.setParam("status_code", "7");
                return msg;
            }
        };
        Authenticator auth = new Authenticator(CredentialStore::empty, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 1, 4, plugin.getLogger("t", CLogger.Level.Info));
        EmbeddedChannel ch = new EmbeddedChannel(new LogStreamerWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "alice", "admin"), gate));
        try {
            ch.writeInbound(new TextWebSocketFrame("region-1,agent-typo,Info,io.cresco"));      // returns at once: off the loop
            ch.writeInbound(new TextWebSocketFrame("region-1,agent-1,Info,io.cresco"));
            Object busy = ch.readOutbound();
            assertNotNull(busy, "the second line is answered at once");
            assertTrue(((TextWebSocketFrame) busy).text().startsWith("busy:"), ((TextWebSocketFrame) busy).text());
        } finally {
            release.countDown();
        }
        long until = System.currentTimeMillis() + 5_000;
        while (actions.size() < 2 && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(List.of("setlogdp@" + LogStreamerWsHandler.CONFIG_RPC_TIMEOUT_MS, "setloglevel@" + LogStreamerWsHandler.CONFIG_RPC_TIMEOUT_MS),
                actions, "the first configuration ran, each RPC bounded");
    }
}
