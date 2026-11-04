package io.cresco.wsapi.auth;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.wsapi.TestSupport;
import io.cresco.wsapi.netty.ApiGateway;
import io.cresco.wsapi.netty.NettyWsServer;
import io.cresco.wsapi.netty.WsRouter;
import io.cresco.wsapi.tls.WsapiKeystore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Revocation reaches open connections: a removed user, a changed password, a changed role, a revoked or expired token and
 * a changed service key close every connection (API, dataplane, log stream) of that credential within the check interval,
 * also a connection that sends nothing; unrelated callers and credentials keep theirs.
 */
class RevocationTest {

    static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        private final StringBuilder part = new StringBuilder();
        volatile int closedWith = 0;
        volatile String closeReason;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            part.append(data);
            if (last) {
                got.add(part.toString());
                part.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closeReason = reason;
            closedWith = code;
            return null;
        }
    }

    final SecureRandom random = new SecureRandom();
    final AtomicReference<CredentialStore> store = new AtomicReference<>();
    final AtomicReference<String> serviceKey = new AtomicReference<>("4f1d6c0e9b2a7d35e8c1f0a6b9d2e47c51a8f3b6d0c9e2a7f4b1d8c5e2a9f6b3");
    final AtomicReference<Set<String>> superusers = new AtomicReference<>(Set.of());
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    NettyWsServer server;
    HttpClient http;
    String toolToken;

    static String rpc() {
        Map<String, Object> m = new HashMap<>();
        m.put("message_info", Map.of("message_type", "agent_msgevent", "message_event_type", "EXEC", "is_rpc", "true"));
        m.put("message_payload", Map.of("action", "ping"));
        return new Gson().toJson(m);
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    CredentialStore.Entry entry(String user, String tenant, String role, String password, List<String> grants) {
        return new CredentialStore.Entry(user, tenant, role, PasswordHash.create(password.toCharArray(), random, 1000), grants);
    }

    @BeforeEach
    void start() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-revocation");
        Map<String, Object> config = new HashMap<>();
        config.put("pluginID", "plugin/9");
        PluginBuilder plugin = TestSupport.pluginBuilder(dir, config, log);
        WsapiKeystore.Material tls = WsapiKeystore.loadOrCreate(new WsapiKeystore.Settings().dataDir(dir)
                .identity("region-1", "agent-1", "plugin/9").hostSans(false), new WsapiKeystore.Log() {
                    @Override public void info(String m) { }
                    @Override public void warn(String m) { }
                });
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String id = "00112233445566ff";
        toolToken = "cwt1_" + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        store.set(CredentialStore.empty()
                .with(entry("alice", "tenant-a", "admin", "alice-Password-k3v9", List.of()))
                .with(entry("bob", "tenant-a", "user", "bob-Password-q7x2z", List.of("shared*")))
                .with(entry("ops", "ops", "admin", "ops-Password-m4n8w", List.of()))
                .withToken(new CredentialStore.Token(id, "alice", CredentialStore.Token.hash(toolToken), System.currentTimeMillis() / 1000, 0, "ci")));
        Authenticator auth = new Authenticator(store::get, serviceKey::get, () -> true, () -> "tenant-a", superusers::get,
                AuthRateLimiter.defaults(), 300_000L, System::currentTimeMillis);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                msg.setParam("status", "10");
                return msg;
            }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 2, 16, TestSupport.logger(log)).revocationCheckMs(250);
        server = new NettyWsServer(plugin, 0, tls.keyStore, tls.password, 64 * 1024, gate);
        server.start();
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("wsapi", tls.certificate);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(ssl).build();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    Client open(String path, String header, String value) throws Exception {
        Client c = new Client();
        http.newWebSocketBuilder().header(header, value).buildAsync(URI.create("wss://localhost:" + server.boundPort() + path), c)
                .get(10, TimeUnit.SECONDS);
        return c;
    }

    WebSocket openWs(Client c, String header, String value) throws Exception {
        return http.newWebSocketBuilder().header(header, value)
                .buildAsync(URI.create("wss://localhost:" + server.boundPort() + "/api/apisocket"), c).get(10, TimeUnit.SECONDS);
    }

    static boolean within(long ms, BooleanSupplier p) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (p.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return p.getAsBoolean();
    }

    static boolean answers(WebSocket ws, Client c) throws Exception {
        c.got.clear();
        ws.sendText(rpc(), true).get(5, TimeUnit.SECONDS);
        return c.got.poll(5, TimeUnit.SECONDS) != null;
    }

    @Test
    void aChangedPasswordClosesEveryConnectionItOpenedOnEveryEndpointButNotTheUsersTokens() throws Exception {
        String alice = basic("alice", "alice-Password-k3v9");
        Client api = new Client();
        WebSocket apiWs = openWs(api, "Authorization", alice);
        Client dp = open("/api/dataplane", "Authorization", alice);
        Client logs = open("/api/logstreamer", "Authorization", alice);
        Client tool = new Client();
        WebSocket toolWs = openWs(tool, "Authorization", "Bearer " + toolToken);
        Client bob = new Client();
        WebSocket bobWs = openWs(bob, "Authorization", basic("bob", "bob-Password-q7x2z"));
        assertTrue(answers(apiWs, api) && answers(toolWs, tool) && answers(bobWs, bob), "control: all of them work");

        store.set(store.get().with(entry("alice", "tenant-a", "admin", "alice-NewPassword-r5t8", List.of())));
        assertTrue(within(3_000, () -> api.closedWith == 1008 && dp.closedWith == 1008 && logs.closedWith == 1008),
                "alice's API, dataplane and log-stream connections (the last two send nothing) closed within 3 s: "
                        + api.closedWith + " " + dp.closedWith + " " + logs.closedWith);
        assertEquals("credentials revoked or changed: authenticate again", dp.closeReason);
        assertTrue(answers(toolWs, tool), "alice's API token is still valid: its connection keeps working");
        assertTrue(answers(bobWs, bob), "bob is not affected");
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARN wsapi closed 3 open connection(s) of api:tenant-a/alice[admin] (password): "
                + "the password of alice was changed")), String.join("\n", log));

        assertThrows(Exception.class, () -> open("/api/apisocket", "Authorization", alice), "the old password opens nothing new");
        Client again = new Client();
        WebSocket againWs = openWs(again, "Authorization", basic("alice", "alice-NewPassword-r5t8"));
        assertTrue(answers(againWs, again), "the new password works");
    }

    @Test
    void aChangedRoleOrGrantsForceANewLoginAndARemovedUserTakesItsTokensConnectionsWithIt() throws Exception {
        Client bob = new Client();
        WebSocket bobWs = openWs(bob, "Authorization", basic("bob", "bob-Password-q7x2z"));
        Client tool = new Client();
        WebSocket toolWs = openWs(tool, "Authorization", "Bearer " + toolToken);
        assertTrue(answers(bobWs, bob) && answers(toolWs, tool), "control");

        CredentialStore.Entry b = store.get().get("bob");
        store.set(store.get().with(new CredentialStore.Entry("bob", "tenant-a", "user", b.hash, List.of("other*"))));
        assertTrue(within(3_000, () -> bob.closedWith == 1008), "bob's stream grants changed: his connection closed");
        assertTrue(answers(toolWs, tool), "alice's token is not affected by bob's change");

        Client bob2 = new Client();
        WebSocket bob2Ws = openWs(bob2, "Authorization", basic("bob", "bob-Password-q7x2z"));
        assertTrue(answers(bob2Ws, bob2), "bob logs in again, with his new grants");
        store.set(store.get().with(new CredentialStore.Entry("bob", "tenant-a", "admin", b.hash, List.of("other*"))));
        assertTrue(within(3_000, () -> bob2.closedWith == 1008), "bob's role changed (user to admin): his connection closed");

        store.set(store.get().without("alice"));
        assertTrue(within(3_000, () -> tool.closedWith == 1008), "alice removed: the connection of her token closed");
        assertTrue(log.stream().anyMatch(l -> l.contains("(API token 00112233445566ff): user alice was removed")), String.join("\n", log));
    }

    @Test
    void aRevokedOrExpiredTokenAndAChangedServiceKeyCloseTheirConnections() throws Exception {
        Client tool = new Client();
        WebSocket toolWs = openWs(tool, "Authorization", "Bearer " + toolToken);
        Client key = new Client();
        WebSocket keyWs = openWs(key, "cresco_service_key", serviceKey.get());
        Client alice = new Client();
        WebSocket aliceWs = openWs(alice, "Authorization", basic("alice", "alice-Password-k3v9"));
        assertTrue(answers(toolWs, tool) && answers(keyWs, key) && answers(aliceWs, alice), "control");

        // a token that expires while its connection is open
        CredentialStore.Token t = store.get().token("00112233445566ff");
        store.set(store.get().withoutToken(t.id).withToken(new CredentialStore.Token(t.id, t.user, t.sha384, t.createdSeconds,
                System.currentTimeMillis() / 1000 + 2, t.label)));
        assertTrue(answers(toolWs, tool), "not expired yet");
        assertTrue(within(5_000, () -> tool.closedWith == 1008), "expired: closed");

        serviceKey.set("9a8b7c6d5e4f30211f2e3d4c5b6a79880f1e2d3c4b5a69788796a5b4c3d2e1f0");
        assertTrue(within(3_000, () -> key.closedWith == 1008), "the service key changed: its connection closed");
        assertTrue(answers(aliceWs, alice), "a password connection is not affected by the service key");
    }

    @Test
    void aSuperuserTenantsAdminLosesItsCrossTenantConnectionsWhenItsTenantIsNoLongerOne() throws Exception {
        superusers.set(Set.of("ops"));
        Client ops = new Client();
        WebSocket opsWs = openWs(ops, "Authorization", basic("ops", "ops-Password-m4n8w"));
        assertTrue(answers(opsWs, ops), "control: an admin of a superuser tenant may use another tenant's wsapi");
        superusers.set(Set.of());
        assertTrue(within(3_000, () -> ops.closedWith == 1008), "no longer a superuser tenant: closed");
    }

    @Test
    void anUnchangedCredentialIsNeverClosedByUnrelatedChanges() throws Exception {
        Client alice = new Client();
        WebSocket aliceWs = openWs(alice, "Authorization", basic("alice", "alice-Password-k3v9"));
        store.set(store.get().with(entry("carol", "tenant-a", "user", "carol-Password-z8y7", List.of())));
        store.set(store.get().with(entry("bob", "tenant-a", "user", "bob-Password-NEW-q7x2z", List.of())));
        Thread.sleep(1_000);
        assertEquals(0, alice.closedWith);
        assertTrue(answers(aliceWs, alice), "alice keeps her connection when other users change");
    }
}
