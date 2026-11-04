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
        Authenticator auth = new Authenticator(() -> store, () -> "the-service-key-value", () -> true, () -> "tenant-a",
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
        WebSocket ws;
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
        Client c = open(WsRouter.APISOCKET_PATH, Map.of("cresco_service_key", "the-service-key-value"));
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
        assertEquals(404, refusedWith("/api/other", Map.of("cresco_service_key", "the-service-key-value")));
        assertTrue(log.stream().noneMatch(l -> l.contains("wrong-password") || l.contains("the-service-key-value")), "secrets are never logged");
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
            assertEquals(429, refusedWith(WsRouter.DATAPLANE_PATH, Map.of("cresco_service_key", "the-service-key-value")));
        } finally {
            port = main;
            strict.stop();
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
