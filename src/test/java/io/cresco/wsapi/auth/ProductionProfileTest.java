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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
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
 * The production profile ({@code security_profile=production}): client certificates only, mapped from their subject
 * alternative names; passwords and the service key refused, API tokens too unless allowed; revocation by deny list and
 * CRL reaches open connections; and wsapi does not start without what production needs.
 */
class ProductionProfileTest {

    static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        private final StringBuilder part = new StringBuilder();
        volatile int closedWith;
        volatile String reason;

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
        public CompletionStage<?> onClose(WebSocket ws, int code, String r) {
            reason = r;
            closedWith = code;
            return null;
        }
    }

    TestPki.Ca ca, otherCa;
    TestPki.Client carol, ops, unnamed, stranger;
    Path dir, caFile, denyFile, crlFile;
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    WsapiKeystore.Material tls;
    NettyWsServer server;
    String token;
    /** A service key that would be strong enough in the debug profile (made at run time, never a literal). */
    final String serviceKey = randomHex();

    static String randomHex() {
        byte[] b = new byte[32];
        TestPki.RANDOM.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }
    final AtomicReference<CredentialStore> store = new AtomicReference<>(CredentialStore.empty());

    @BeforeEach
    void pki() throws Exception {
        dir = Files.createTempDirectory("wsapi-production");
        ca = TestPki.ca("Cresco test client CA");
        otherCa = TestPki.ca("Someone else's CA");
        carol = TestPki.client(ca, "carol", "cresco:api:tenant-a/carol", "cresco:role:user", "cresco:grant:job*");
        ops = TestPki.client(ca, "ops", "cresco:api:tenant-a/ops", "cresco:role:admin");
        unnamed = TestPki.client(ca, "unnamed");
        stranger = TestPki.client(otherCa, "stranger", "cresco:api:tenant-a/stranger", "cresco:role:admin");
        caFile = TestPki.writePem(dir.resolve("client-ca.pem"), ca.cert);
        denyFile = Files.writeString(dir.resolve("deny.txt"), "# revoked client certificates\n");
        Files.setPosixFilePermissions(denyFile, PosixFilePermissions.fromString("rw-r--r--"));
        crlFile = TestPki.writeCrl(dir.resolve("client.crl"), TestPki.crl(ca));
        byte[] secret = new byte[32];
        TestPki.RANDOM.nextBytes(secret);
        String tokenId = "0011223344556677";
        token = "cwt1_" + tokenId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        store.set(CredentialStore.empty()
                .with(new CredentialStore.Entry("tool", "tenant-a", "admin", PasswordHash.create("tool-Password-k3v9".toCharArray(), TestPki.RANDOM, 1000)))
                .withToken(new CredentialStore.Token(tokenId, "tool", CredentialStore.Token.hash(token), System.currentTimeMillis() / 1000, 0, "ci")));
    }

    void start(boolean allowTokens) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("pluginID", "plugin/9");
        PluginBuilder plugin = TestSupport.pluginBuilder(dir, config, log);
        tls = WsapiKeystore.loadOrCreate(new WsapiKeystore.Settings().dataDir(dir).identity("region-1", "agent-1", "plugin/9").hostSans(false),
                new WsapiKeystore.Log() {
                    @Override public void info(String m) { }
                    @Override public void warn(String m) { }
                });
        SecurityProfile.Production prod = SecurityProfile.check(caFile.toString(), denyFile.toString(), crlFile.toString(), null, true,
                System::currentTimeMillis);
        assertEquals(List.of(), prod.problems, "control: production can start");
        Authenticator auth = new Authenticator(store::get, () -> serviceKey,
                () -> true, () -> "tenant-a", Set::of, AuthRateLimiter.defaults(), 0L, System::currentTimeMillis)
                .production(prod.certificates, allowTokens);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                msg.setParam("status", "10");
                msg.setParam("caller", caller.toString());
                return msg;
            }
        };
        server = TestSupport.startReachable(() -> new NettyWsServer(plugin, 0, tls.keyStore, tls.password, 64 * 1024,
                new WsRouter.Gate(auth, gw, 2, 16, TestSupport.logger(log)).revocationCheckMs(250)), tls.certificate);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    HttpClient http(TestPki.Client cert) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("wsapi", tls.certificate);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        KeyManagerFactory kmf = null;
        if (cert != null) {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(cert.keyStore(), "changeit".toCharArray());
        }
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    URI uri(String path) { return URI.create("wss://localhost:" + server.boundPort() + path); }

    /** 101 when the upgrade succeeds, the HTTP status when it is refused, -1 when TLS refuses the connection. */
    int status(TestPki.Client cert, String header, String value) throws Exception {
        try {
            WebSocket.Builder b = http(cert).newWebSocketBuilder();
            if (header != null) b.header(header, value);
            WebSocket ws = b.buildAsync(uri("/api/apisocket"), new Client()).get(10, TimeUnit.SECONDS);
            ws.abort();
            return 101;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof WebSocketHandshakeException) return ((WebSocketHandshakeException) c).getResponse().statusCode();
            return -1;
        }
    }

    WebSocket open(TestPki.Client cert, Client c, String path) throws Exception {
        return http(cert).newWebSocketBuilder().buildAsync(uri(path), c).get(10, TimeUnit.SECONDS);
    }

    static String rpc() {
        Map<String, Object> m = new HashMap<>();
        m.put("message_info", Map.of("message_type", "agent_msgevent", "message_event_type", "EXEC", "is_rpc", "true"));
        m.put("message_payload", Map.of("action", "ping"));
        return new Gson().toJson(m);
    }

    static boolean within(long ms, BooleanSupplier p) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (p.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return p.getAsBoolean();
    }

    @Test
    void anAssignedCertificateIsTheCallerItNamesAndNothingElseGetsIn() throws Exception {
        start(false);
        Client c = new Client();
        WebSocket ws = open(carol, c, "/api/apisocket");
        ws.sendText(rpc(), true).get(5, TimeUnit.SECONDS);
        String reply = c.got.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply);
        assertTrue(reply.contains("api:tenant-a/carol[user]"), "the caller is the certificate's: " + reply);
        assertTrue(log.stream().anyMatch(l -> l.contains("authenticated as api:tenant-a/carol[user] (client certificate sha256:")), String.join("\n", log));

        assertEquals(-1, status(null, null, null), "no client certificate: TLS refuses the connection");
        assertEquals(-1, status(stranger, null, null), "a certificate of another CA: TLS refuses it");
        assertEquals(403, status(unnamed, null, null), "a certificate naming no Cresco caller");
        String basic = "Basic " + Base64.getEncoder().encodeToString("tool:tool-Password-k3v9".getBytes());
        assertEquals(401, status(carol, "Authorization", basic), "a password, even over a valid certificate");
        assertEquals(401, status(carol, "cresco_service_key", serviceKey), "the service key");
        assertEquals(401, status(carol, "Authorization", "Bearer " + token), "an API token (wsapi_allow_tokens=false)");
        assertTrue(log.stream().anyMatch(l -> l.contains("a password, which production refuses")), String.join("\n", log));
        assertTrue(log.stream().anyMatch(l -> l.contains("an API token, which production refuses")), String.join("\n", log));
        assertTrue(log.stream().anyMatch(l -> l.contains("names no Cresco API caller")), String.join("\n", log));
    }

    @Test
    void tokensWorkOverAClientCertificateOnlyWhenAllowed() throws Exception {
        start(true);
        assertEquals(101, status(carol, "Authorization", "Bearer " + token), "allowed: the token over carol's certificate");
        assertTrue(log.stream().anyMatch(l -> l.contains("authenticated as api:tenant-a/tool[admin] (API token 0011223344556677)")), String.join("\n", log));
        assertEquals(-1, status(null, "Authorization", "Bearer " + token), "still not without a client certificate");
        String basic = "Basic " + Base64.getEncoder().encodeToString("tool:tool-Password-k3v9".getBytes());
        assertEquals(401, status(carol, "Authorization", basic), "passwords stay refused");
    }

    @Test
    void theDenyListAndTheCrlRevokeOpenConnectionsAndAnUncheckableListAcceptsNone() throws Exception {
        start(false);
        Client c1 = new Client(), c2 = new Client(), c3 = new Client();
        open(carol, c1, "/api/apisocket");
        open(ops, c2, "/api/dataplane");
        open(ops, c3, "/api/logstreamer");

        Files.writeString(denyFile, "# revoked\nsha256:" + ClientCertificates.fingerprint(carol.cert).toUpperCase() + "\n");
        assertTrue(within(4_000, () -> c1.closedWith == 1008), "carol on the deny list: her open connection closed, " + c1.closedWith);
        assertEquals(0, c2.closedWith + c3.closedWith, "ops is not affected");
        assertEquals(401, status(carol, null, null), "and a new one is refused");
        assertTrue(log.stream().anyMatch(l -> l.contains("is on the deny list (wsapi_client_deny_file)")), String.join("\n", log));

        TestPki.writeCrl(crlFile, TestPki.crl(ca, ops.cert));
        assertTrue(within(4_000, () -> c2.closedWith == 1008 && c3.closedWith == 1008), "ops revoked by the CRL: dataplane and log stream closed");
        assertEquals(401, status(ops, null, null));
        assertTrue(log.stream().anyMatch(l -> l.contains("is revoked by the CRL (wsapi_client_crl)")), String.join("\n", log));

        // a CRL that does not verify (signed by another CA): no client certificate is accepted (fail closed)
        TestPki.Client dave = TestPki.client(ca, "dave", "cresco:api:tenant-a/dave", "cresco:role:user");
        Client c4 = new Client();
        TestPki.writeCrl(crlFile, TestPki.crl(ca));
        Thread.sleep(1_200);
        open(dave, c4, "/api/apisocket");
        TestPki.Ca impostor = TestPki.ca(ca.cert.getSubjectX500Principal().getName().substring(3));
        TestPki.writeCrl(crlFile, TestPki.crl(impostor));
        assertTrue(within(4_000, () -> c4.closedWith == 1008), "an unverifiable CRL closes every certificate connection");
        assertEquals(401, status(dave, null, null));
        TestPki.writeCrl(crlFile, TestPki.crl(ca));
        assertTrue(within(4_000, () -> {
            try {
                return status(dave, null, null) == 101;
            } catch (Exception e) {
                return false;
            }
        }), "a valid CRL again: accepted again");

        // a deny list others may write is not trusted either
        Files.setPosixFilePermissions(denyFile, PosixFilePermissions.fromString("rw-rw-r--"));
        Thread.sleep(1_200);
        assertEquals(401, status(dave, null, null), "a group-writable deny list accepts no certificate");
        assertTrue(log.stream().anyMatch(l -> l.contains("is writable by others")), String.join("\n", log));
    }

    @Test
    void aCertificateThatExpiresWhileConnectedIsClosed() throws Exception {
        start(false);
        TestPki.Client brief = TestPki.client(ca, "brief", 5_000L, "cresco:api:tenant-a/brief", "cresco:role:user");
        Client c = new Client();
        open(brief, c, "/api/apisocket");
        assertTrue(within(12_000, () -> c.closedWith == 1008), "closed when it expired");
        assertTrue(log.stream().anyMatch(l -> l.contains("its client certificate expired at")), String.join("\n", log));
    }

    @Test
    void productionDoesNotStartWithoutItsClientCaOrWithADisabledMethodOn() throws Exception {
        assertTrue(SecurityProfile.isProduction("production") && SecurityProfile.isProduction(" PRODUCTION ")
                && !SecurityProfile.isProduction("debug") && !SecurityProfile.isProduction(null));
        assertTrue(SecurityProfile.check(null, null, null, null, true, System::currentTimeMillis).problems.get(0)
                .contains("wsapi_client_ca is not set"));
        assertTrue(SecurityProfile.check(dir.resolve("missing.pem").toString(), null, null, null, true, System::currentTimeMillis)
                .problems.get(0).contains("does not exist"));
        Path notPem = Files.writeString(dir.resolve("not.pem"), "hello");
        assertFalse(SecurityProfile.check(notPem.toString(), null, null, null, true, System::currentTimeMillis).problems.isEmpty());
        List<String> key = SecurityProfile.check(caFile.toString(), null, null, "some-configured-key", true, System::currentTimeMillis).problems;
        assertTrue(key.size() == 1 && key.get(0).contains("the service key is configured"), key.toString());
        assertEquals(List.of(), SecurityProfile.check(caFile.toString(), null, null, "some-configured-key", false, System::currentTimeMillis).problems,
                "a service key that is disabled is fine");
        assertEquals(List.of(), SecurityProfile.check(caFile.toString(), null, null, "", true, System::currentTimeMillis).problems,
                "an empty key is no key");
        Path badDeny = Files.writeString(dir.resolve("bad-deny.txt"), "not a fingerprint\n");
        Files.setPosixFilePermissions(badDeny, PosixFilePermissions.fromString("rw-------"));
        assertTrue(SecurityProfile.check(caFile.toString(), badDeny.toString(), null, null, false, System::currentTimeMillis)
                .problems.get(0).contains("is not a SHA-256 fingerprint"));
        Path foreignCrl = TestPki.writeCrl(dir.resolve("foreign.crl"), TestPki.crl(otherCa));
        assertTrue(SecurityProfile.check(caFile.toString(), null, foreignCrl.toString(), null, false, System::currentTimeMillis)
                .problems.get(0).contains("is not signed by a CA of wsapi_client_ca"));
        assertNull(SecurityProfile.check(null, null, null, null, true, System::currentTimeMillis).certificates);
    }

    @Test
    void theMappingNeedsOneCallerAndOneRole() throws Exception {
        ClientCertificates.Mapping m = ClientCertificates.map(carol.cert);
        assertEquals("tenant-a", m.tenant);
        assertEquals("carol", m.user);
        assertEquals("user", m.role);
        assertEquals(List.of("job*"), m.grants);
        String[][] bad = {
                {"cresco:role:user"},
                {"cresco:api:tenant-a/x", "cresco:api:tenant-a/y", "cresco:role:user"},
                {"cresco:api:tenant-a/x", "cresco:role:user", "cresco:role:admin"},
                {"cresco:api:tenant-a/x"},
                {"cresco:api:tenant-a/x", "cresco:role:root"},
                {"cresco:api:not a tenant/x", "cresco:role:user"},
                {"cresco:api:tenant-a/service-key", "cresco:role:admin"},
                {"cresco:api:tenant-a/x", "cresco:role:user", "cresco:grant:bad grant"},
        };
        for (String[] uris : bad) {
            TestPki.Client c = TestPki.client(ca, "x", uris);
            assertThrows(ClientCertificates.Invalid.class, () -> ClientCertificates.map(c.cert), String.join(",", uris));
        }
    }
}
