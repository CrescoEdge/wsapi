package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.wsapi.TestSupport;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
import io.cresco.wsapi.auth.PasswordHash;
import io.cresco.wsapi.tls.WsapiKeystore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wsapi server end to end, over real TLS: the generated certificate, authentication of every upgrade, the
 * caller each message is sent for, and the refusals a client sees. The controller is replaced by a gateway that
 * records what wsapi asked it to send.
 */
class WsapiServerAuthTest {

    static Path dir;
    static NettyWsServer server;
    static WsapiKeystore.Material tls;
    static int port;
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());
    static final List<Object[]> sent = Collections.synchronizedList(new ArrayList<>());   // {MsgEvent, Principal, "send"|"rpc"}
    static SSLContext trusting;

    static PluginBuilder plugin;
    static CredentialStore store;

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("wsapi-it");
        Map<String, Object> config = new HashMap<>();
        config.put("pluginID", "plugin/9");
        config.put("wsapi_upgrade_timeout_seconds", "2");
        config.put("wsapi_api_max_message_bytes", "65536");
        config.put("wsapi_dataplane_max_message_bytes", "131072");
        plugin = TestSupport.pluginBuilder(dir, config, log);
        tls = WsapiKeystore.loadOrCreate(new WsapiKeystore.Settings().dataDir(dir).identity("region-1", "agent-1", "plugin/9").hostSans(false),
                new WsapiKeystore.Log() {
                    @Override public void info(String m) { log.add("INFO " + m); }
                    @Override public void warn(String m) { log.add("WARN " + m); }
                });
        SecureRandom r = new SecureRandom();
        store = CredentialStore.parse(
                "alice:tenant-a:admin:" + PasswordHash.create("alice-password-1".toCharArray(), r).encode() + "\n"
                        + "bob:tenant-a:user:" + PasswordHash.create("bob-password-22".toCharArray(), r).encode() + "\n"
                        + "eve:tenant-b:user:" + PasswordHash.create("eve-password-333".toCharArray(), r).encode() + "\n"
                        + "root:ops:admin:" + PasswordHash.create("root-password-4444".toCharArray(), r).encode() + "\n", "t");
        // generous limits here; the rate-limit test runs its own server with strict ones
        server = startServer(new AuthRateLimiter(1000, 1000, 60_000L, 60_000L, 900_000L, 1000, System::currentTimeMillis));
        port = server.boundPort();

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("wsapi", tls.certificate);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        trusting = SSLContext.getInstance("TLS");
        trusting.init(null, tmf.getTrustManagers(), null);
    }

    static NettyWsServer startServer(AuthRateLimiter limiter) throws Exception {
        return startServer(limiter, Set.of());
    }

    static NettyWsServer startServer(AuthRateLimiter limiter, Set<String> superusers) throws Exception {
        Authenticator auth = new Authenticator(() -> store, () -> "the-service-key-value-0123456789abcdef", () -> true, () -> "tenant-a",
                () -> superusers, limiter, 300_000L, System::currentTimeMillis);
        ApiGateway recorder = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { sent.add(new Object[]{msg, caller, "send"}); }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                sent.add(new Object[]{msg, caller, "rpc"});
                msg.setParam("status", "10");
                return msg;
            }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, recorder, 2, 16, plugin.getLogger("t", io.cresco.library.utilities.CLogger.Level.Info));
        NettyWsServer s = new NettyWsServer(plugin, 0, tls.keyStore, tls.password, 64 * 1024, gate);
        s.start();
        return s;
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop();
    }

    static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        final java.util.concurrent.CompletableFuture<Integer> closed = new java.util.concurrent.CompletableFuture<>();
        volatile String closeReason;
        WebSocket ws;

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReason = reason;
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(-1);
        }
        private final StringBuilder part = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            part.append(data);
            if (last) {
                got.add(part.toString());
                part.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }

    static Client open(String path, Map<String, String> headers) throws Exception {
        Client c = new Client();
        WebSocket.Builder b = HttpClient.newBuilder().sslContext(trusting).build().newWebSocketBuilder();
        headers.forEach(b::header);
        c.ws = b.buildAsync(URI.create("wss://localhost:" + port + path), c).get(20, TimeUnit.SECONDS);
        return c;
    }

    static int refusedWith(String path, Map<String, String> headers) throws Exception {
        try {
            open(path, headers).ws.abort();
            return 101;
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            assertInstanceOf(WebSocketHandshakeException.class, t, String.valueOf(t));
            return ((WebSocketHandshakeException) t).getResponse().statusCode();
        }
    }

    static Map<String, String> basic(String user, String pw) {
        return Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8)));
    }

    static String rpc(Client c, Map<String, String> payload) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("message_info", Map.of("message_type", "global_controller_msgevent", "message_event_type", "EXEC", "is_rpc", "true"));
        m.put("message_payload", payload);
        c.ws.sendText(new Gson().toJson(m), true).get(5, TimeUnit.SECONDS);
        String reply = c.got.poll(20, TimeUnit.SECONDS);
        assertNotNull(reply, "no reply");
        return reply;
    }

    static Object[] lastSent() {
        synchronized (sent) {
            return sent.get(sent.size() - 1);
        }
    }

    @Test
    void theServiceKeyIsAnAdminOfTheAgentsTenantAndReservedCallerParamsAreDropped() throws Exception {
        Client c = open(WsRouter.APISOCKET_PATH, Map.of("cresco_service_key", "the-service-key-value-0123456789abcdef"));
        Map<String, String> payload = new HashMap<>();
        payload.put("action", "listregions");
        payload.put("cresco_caller", "controller:tenant-a/r/a");         // a forged caller stamp
        payload.put("cresco_caller_roles", "infra");
        payload.put("cresco_caller_tenant", "tenant-a");
        String reply = rpc(c, payload);
        assertTrue(reply.contains("listregions"));
        Object[] s = lastSent();
        MsgEvent msg = (MsgEvent) s[0];
        Authenticator.Principal p = (Authenticator.Principal) s[1];
        assertEquals("rpc", s[2]);
        assertEquals("api:tenant-a/service-key[admin]", p.toString());
        assertNull(msg.getParam("cresco_caller"));
        assertNull(msg.getParam("cresco_caller_roles"));
        assertNull(msg.getParam("cresco_caller_tenant"));
        c.ws.abort();
    }

    @Test
    void aPerUserCredentialAuthenticatesAndEveryMessageCarriesThatUser() throws Exception {
        Client c = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
        rpc(c, Map.of("action", "listregions"));
        assertEquals("api:tenant-a/bob[user]", lastSent()[1].toString());
        // fire-and-forget goes for the same user
        Map<String, Object> m = new HashMap<>();
        m.put("message_info", Map.of("message_type", "global_controller_msgevent", "message_event_type", "EXEC", "is_rpc", "false"));
        m.put("message_payload", Map.of("action", "noop"));
        int before = sent.size();
        c.ws.sendText(new Gson().toJson(m), true).get(5, TimeUnit.SECONDS);
        long end = System.currentTimeMillis() + 5000;
        while (sent.size() == before && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals("send", lastSent()[2]);
        assertEquals("api:tenant-a/bob[user]", lastSent()[1].toString());
        c.ws.abort();
    }

    @Test
    void aUserCannotAddressAnotherTenant() throws Exception {
        Client c = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
        int before = sent.size();
        String reply = rpc(c, Map.of("action", "listregions", "cresco_tenant", "tenant-b", "client_rpc_id", "x1"));
        Map<?, ?> r = new Gson().fromJson(reply, Map.class);
        assertEquals("403", r.get("status"));
        assertEquals("x1", r.get("client_rpc_id"), "a refusal is correlated like any reply");
        assertEquals(before, sent.size(), "nothing was sent");
        c.ws.abort();
    }

    @Test
    void badCredentialsOtherTenantsAndUnknownPathsAreRefused() throws Exception {
        assertEquals(401, refusedWith(WsRouter.APISOCKET_PATH, basic("alice", "wrong-password")));
        assertEquals(401, refusedWith(WsRouter.DATAPLANE_PATH, Map.of("cresco_service_key", "wrong")));
        assertEquals(401, refusedWith(WsRouter.LOGSTREAMER_PATH, Map.of()));
        assertEquals(403, refusedWith(WsRouter.APISOCKET_PATH, basic("eve", "eve-password-333")));
        assertEquals(404, refusedWith("/api/other", Map.of("cresco_service_key", "the-service-key-value-0123456789abcdef")));
        assertTrue(log.stream().noneMatch(l -> l.contains("wrong-password") || l.contains("the-service-key-value-0123456789abcdef")), "secrets are never logged");
        assertTrue(log.stream().anyMatch(l -> l.contains("refused") && l.contains("alice") && l.contains("401")));
    }

    @Test
    void repeatedFailuresFromOneSourceAreRateLimited() throws Exception {
        NettyWsServer strict = startServer(new AuthRateLimiter(3, 50, 60_000L, 60_000L, 900_000L, 1000, System::currentTimeMillis));
        int main = port;
        try {
            port = strict.boundPort();
            assertEquals(401, refusedWith(WsRouter.APISOCKET_PATH, basic("nobody", "guess-1")));
            assertEquals(401, refusedWith(WsRouter.APISOCKET_PATH, basic("alice", "guess-2")));
            assertEquals(401, refusedWith(WsRouter.APISOCKET_PATH, Map.of("cresco_service_key", "guess-3")));
            assertEquals(429, refusedWith(WsRouter.APISOCKET_PATH, basic("alice", "alice-password-1")), "locked even with the right password");
            assertEquals(429, refusedWith(WsRouter.DATAPLANE_PATH, Map.of("cresco_service_key", "the-service-key-value-0123456789abcdef")));
        } finally {
            port = main;
            strict.stop();
        }
    }

    @Test
    void aConnectionThatNeverUpgradesIsClosedAtTheDeadline() throws Exception {
        long before = NettyWsServer.upgradeTimeouts.get();
        javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) trusting.getSocketFactory().createSocket("localhost", port);
        s.startHandshake();                                           // TLS done, then nothing: no upgrade request
        s.setSoTimeout(10_000);
        long t0 = System.currentTimeMillis();
        int r;
        try {
            r = s.getInputStream().read();
        } catch (java.io.IOException closed) {
            r = -1;
        }
        long waited = System.currentTimeMillis() - t0;
        assertEquals(-1, r, "the server closed the idle connection");
        assertTrue(waited >= 1000 && waited < 9000, "closed at the deadline, after " + waited + " ms");
        assertTrue(NettyWsServer.upgradeTimeouts.get() > before);
        s.close();
        // a client that upgrades in time keeps its connection past the deadline
        Client c = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
        Thread.sleep(2500);
        rpc(c, Map.of("action", "listregions"));
        c.ws.abort();
    }

    static String text(int n) {
        return "x".repeat(n);
    }

    @Test
    void anOversizedApiMessageClosesTheConnectionWith1009() throws Exception {
        // within the cap (64 KiB here): answered
        Client ok = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
        assertTrue(rpc(ok, Map.of("action", "listregions", "blob", text(60_000))).contains("listregions"));
        ok.ws.abort();
        // one frame over the cap: the decoder closes with 1009
        Client one = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
        one.ws.sendText(text(70_000), true);
        assertEquals(1009, one.closed.get(10, TimeUnit.SECONDS));
        // fragments that add up to more than the cap: the aggregator closes with 1009 and names the setting
        Client frag = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
        frag.ws.sendText(text(40_000), false).get(5, TimeUnit.SECONDS);
        frag.ws.sendText(text(40_000), true);
        assertEquals(1009, frag.closed.get(10, TimeUnit.SECONDS));
        assertTrue(String.valueOf(frag.closeReason).contains("wsapi_api_max_message_bytes"), frag.closeReason);
    }

    @Test
    void theDataplaneAndTheLogStreamHaveTheirOwnCaps() throws Exception {
        // the dataplane (128 KiB here) takes a 100 KB frame that the API socket would refuse ...
        Client dp = open(WsRouter.DATAPLANE_PATH, basic("bob", "bob-password-22"));
        dp.ws.sendBinary(java.nio.ByteBuffer.wrap(new byte[100_000]), true).get(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        assertFalse(dp.closed.isDone(), "a frame within the dataplane cap keeps the stream open");
        // ... and closes with 1009 over its own cap
        dp.ws.sendBinary(java.nio.ByteBuffer.wrap(new byte[140_000]), true);
        assertEquals(1009, dp.closed.get(10, TimeUnit.SECONDS));
        // the log stream takes short configuration lines only (default 64 KiB)
        Client ls = open(WsRouter.LOGSTREAMER_PATH, basic("alice", "alice-password-1"));
        ls.ws.sendText(text(70_000), true);
        assertEquals(1009, ls.closed.get(10, TimeUnit.SECONDS));
    }

    @Test
    void capsComeFromConfigWithinBounds() {
        assertEquals(64 << 20, WsRouter.maxMessageBytes(null, WsRouter.APISOCKET_PATH));
        assertEquals(64 << 10, WsRouter.maxMessageBytes(null, WsRouter.LOGSTREAMER_PATH));
        assertEquals(64 << 20, WsRouter.maxMessageBytes(null, WsRouter.DATAPLANE_PATH));
        assertEquals(65536, WsRouter.maxMessageBytes(plugin, WsRouter.APISOCKET_PATH));
        assertEquals(131072, WsRouter.maxMessageBytes(plugin, WsRouter.DATAPLANE_PATH));
        assertEquals("wsapi_dataplane_max_message_bytes", WsRouter.capKey(WsRouter.DATAPLANE_PATH));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("pluginID", "plugin/8");
        cfg.put("wsapi_api_max_message_bytes", "5");
        cfg.put("wsapi_dataplane_max_message_bytes", "999999999999");
        cfg.put("wsapi_logstreamer_max_message_bytes", "lots");
        PluginBuilder pb = TestSupport.pluginBuilder(dir, cfg, log);
        assertEquals(1024, WsRouter.maxMessageBytes(pb, WsRouter.APISOCKET_PATH), "at least 1 KiB");
        assertEquals(64 << 20, WsRouter.maxMessageBytes(pb, WsRouter.DATAPLANE_PATH), "not an int: the default");
        assertEquals(64 << 10, WsRouter.maxMessageBytes(pb, WsRouter.LOGSTREAMER_PATH), "not a number: the default");
    }

    @Test
    void aBrowserOriginIsRefusedAndNoBasicChallengeIsEverSent() throws Exception {
        Map<String, String> h = new HashMap<>(basic("bob", "bob-password-22"));
        h.put("Origin", "https://evil.example");
        assertEquals(403, refusedWith(WsRouter.APISOCKET_PATH, h), "a web page's Origin");
        Map<String, String> own = new HashMap<>(basic("bob", "bob-password-22"));
        own.put("Origin", "https://localhost:" + port);
        assertEquals(101, refusedWith(WsRouter.APISOCKET_PATH, own), "wsapi's own origin, as older Java clients send it");
        try {
            open(WsRouter.APISOCKET_PATH, basic("alice", "wrong-password")).ws.abort();
            fail("a wrong password was accepted");
        } catch (java.util.concurrent.ExecutionException e) {
            WebSocketHandshakeException w = (WebSocketHandshakeException) e.getCause();
            assertEquals(401, w.getResponse().statusCode());
            assertTrue(w.getResponse().headers().firstValue("WWW-Authenticate").isEmpty(), "never a Basic challenge");
        }
    }

    @Test
    void connectionsPerUserAndInFlightRpcsPerUserAreBounded() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("pluginID", "plugin/7");
        cfg.put("wsapi_rpc_max_inflight_user", "2");
        PluginBuilder pb = TestSupport.pluginBuilder(dir, cfg, log);
        Authenticator auth = new Authenticator(() -> store, () -> null, () -> true, () -> "tenant-a", Set::of,
                new AuthRateLimiter(1000, 1000, 60_000L, 60_000L, 900_000L, 1000, System::currentTimeMillis), 300_000L, System::currentTimeMillis);
        ApiGateway slow = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                msg.setParam("status", "10");
                return msg;
            }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, slow, 2, 16, null).limits(2, 2, 8, List.of());
        NettyWsServer s = new NettyWsServer(pb, 0, tls.keyStore, tls.password, 64 * 1024, gate);
        s.start();
        int main = port;
        try {
            port = s.boundPort();
            Client a = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
            Client b = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
            assertEquals(429, refusedWith(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22")), "a third connection for bob");
            for (int i = 1; i <= 3; i++) {
                Map<String, Object> m = new HashMap<>();
                m.put("message_info", Map.of("message_type", "plugin_msgevent", "message_event_type", "EXEC", "dst_plugin", "p", "is_rpc", "true"));
                m.put("message_payload", Map.of("action", "slow", "client_rpc_id", "r" + i));
                a.ws.sendText(new Gson().toJson(m), true).get(5, TimeUnit.SECONDS);
            }
            String busy = a.got.poll(10, TimeUnit.SECONDS);
            assertNotNull(busy, "the over-limit request is answered at once");
            Map<?, ?> r = new Gson().fromJson(busy, Map.class);
            assertEquals("429", r.get("status"), busy);
            assertEquals("r3", r.get("client_rpc_id"));
            release.countDown();
            assertNotNull(a.got.poll(10, TimeUnit.SECONDS));
            assertNotNull(a.got.poll(10, TimeUnit.SECONDS));
            a.ws.abort();
            b.ws.abort();
            Thread.sleep(300);
            Client c = open(WsRouter.APISOCKET_PATH, basic("bob", "bob-password-22"));
            c.ws.abort();
        } finally {
            release.countDown();
            port = main;
            s.stop();
        }
    }

    @Test
    void theDataplaneIsOnlyForCallersOfTheAgentsTenant() throws Exception {
        NettyWsServer su = startServer(new AuthRateLimiter(1000, 1000, 60_000L, 60_000L, 900_000L, 1000, System::currentTimeMillis), Set.of("ops"));
        int main = port;
        try {
            port = su.boundPort();
            // an admin of a superuser tenant authenticates (it may use the API socket) ...
            Client api = open(WsRouter.APISOCKET_PATH, basic("root", "root-password-4444"));
            api.ws.abort();
            // ... but the dataplane is the agent tenant's
            Client dp = open(WsRouter.DATAPLANE_PATH, basic("root", "root-password-4444"));
            dp.ws.sendText("{\"ident_key\":\"stream_name\",\"ident_id\":\"s1\"}", true).get(5, TimeUnit.SECONDS);
            String reply = dp.got.poll(10, TimeUnit.SECONDS);
            assertNotNull(reply);
            Map<?, ?> r = new Gson().fromJson(reply, Map.class);
            assertEquals("9", r.get("status_code"));
            assertTrue(String.valueOf(r.get("status_desc")).contains("tenant-a"), reply);
            dp.ws.abort();
        } finally {
            port = main;
            su.stop();
        }
    }

    @Test
    void theLogStreamSendsItsConfigurationForTheClient() throws Exception {
        Client c = open(WsRouter.LOGSTREAMER_PATH, basic("alice", "alice-password-1"));
        assertNotNull(c.got.poll(10, TimeUnit.SECONDS), "activation reply");
        int before = sent.size();
        c.ws.sendText("region-1,agent-1,Trace,default", true).get(5, TimeUnit.SECONDS);
        assertNotNull(c.got.poll(10, TimeUnit.SECONDS));
        List<Object[]> rpcs;
        synchronized (sent) { rpcs = new ArrayList<>(sent.subList(before, sent.size())); }
        assertFalse(rpcs.isEmpty());
        for (Object[] s : rpcs) assertEquals("api:tenant-a/alice[admin]", s[1].toString());
        c.ws.abort();
    }
}
