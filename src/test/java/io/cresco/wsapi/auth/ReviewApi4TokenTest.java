package io.cresco.wsapi.auth;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.wsapi.TestSupport;
import io.cresco.wsapi.netty.ApiGateway;
import io.cresco.wsapi.netty.NettyWsServer;
import io.cresco.wsapi.netty.WsRouter;
import io.cresco.wsapi.tls.WsapiKeystore;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 4 (review/api-20261104): revoking an API token stops NEW connections with it within a second,
 * but every connection the token already opened stays authenticated and keeps working, for as long as it stays open.
 * The same holds for a removed user or a changed password. A leaked token used by a long-lived client (the GaiaKeep
 * tooling holds its connections open) is not cut off by revocation.
 */
class ReviewApi4TokenTest {

    static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        private final StringBuilder part = new StringBuilder();
        volatile int closedWith = 0;

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
            closedWith = code;
            return null;
        }
    }

    static String rpc() {
        Map<String, Object> m = new HashMap<>();
        m.put("message_info", Map.of("message_type", "agent_msgevent", "message_event_type", "EXEC", "is_rpc", "true"));
        m.put("message_payload", Map.of("action", "ping"));
        return new Gson().toJson(m);
    }

    @Test
    void aRevokedTokenStopsWorkingOnConnectionsItAlreadyOpened() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-review-token");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> config = new HashMap<>();
        config.put("pluginID", "plugin/9");
        PluginBuilder plugin = TestSupport.pluginBuilder(dir, config, log);
        WsapiKeystore.Material tls = WsapiKeystore.loadOrCreate(new WsapiKeystore.Settings().dataDir(dir)
                .identity("region-1", "agent-1", "plugin/9").hostSans(false), new WsapiKeystore.Log() {
                    @Override public void info(String m) { }
                    @Override public void warn(String m) { }
                });
        SecureRandom r = new SecureRandom();
        byte[] secret = new byte[32];
        r.nextBytes(secret);
        String id = "0123456789abcdef";
        String token = "cwt1_" + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        CredentialStore base = CredentialStore.empty()
                .with(new CredentialStore.Entry("tool", "tenant-a", "admin", PasswordHash.create("tool-Password-x9q2".toCharArray(), r, 1000)));
        AtomicReference<CredentialStore> store = new AtomicReference<>(base.withToken(
                new CredentialStore.Token(id, "tool", CredentialStore.Token.hash(token), System.currentTimeMillis() / 1000, 0, "ci")));
        Authenticator auth = new Authenticator(store::get, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 300_000L, System::currentTimeMillis);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                msg.setParam("status", "10");
                return msg;
            }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 2, 16, plugin.getLogger("t", io.cresco.library.utilities.CLogger.Level.Info));
        NettyWsServer server = new NettyWsServer(plugin, 0, tls.keyStore, tls.password, 64 * 1024, gate);
        server.start();
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("wsapi", tls.certificate);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            HttpClient http = HttpClient.newBuilder().sslContext(ssl).build();
            URI api = URI.create("wss://localhost:" + server.boundPort() + "/api/apisocket");

            Client c = new Client();
            WebSocket ws = http.newWebSocketBuilder().header("Authorization", "Bearer " + token).buildAsync(api, c).get(10, TimeUnit.SECONDS);
            ws.sendText(rpc(), true).get(5, TimeUnit.SECONDS);
            assertNotNull(c.got.poll(5, TimeUnit.SECONDS), "control: the token works");

            store.set(store.get().withoutToken(id));                  // revoked
            Thread.sleep(1_500);
            String newConnection;
            try {
                http.newWebSocketBuilder().header("Authorization", "Bearer " + token).buildAsync(api, new Client()).get(10, TimeUnit.SECONDS);
                newConnection = "accepted";
            } catch (Exception refused) {
                newConnection = "refused";
            }
            assertEquals("refused", newConnection, "control: a new connection with the revoked token is refused");

            String reply = null;
            try {
                ws.sendText(rpc(), true).get(5, TimeUnit.SECONDS);
                reply = c.got.poll(5, TimeUnit.SECONDS);
            } catch (Exception closed) {
                // the connection was closed: what revocation should do
            }
            assertNull(reply, "the revoked token's open connection still had its request answered: " + reply);
        } finally {
            server.stop();
        }
    }
}
