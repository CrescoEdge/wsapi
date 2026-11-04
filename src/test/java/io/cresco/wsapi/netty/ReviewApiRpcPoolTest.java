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
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review (review/api-20261104): one ordinary API user (role user) freezes wsapi for every other client.
 *
 * <p>API RPCs are answered from one JVM-wide pool of at most 64 threads with CallerRunsPolicy, and a client chooses
 * each RPC's timeout up to one hour (message_info.rpc_timeout_ms). 64 RPCs that get no reply (a non-existent agent,
 * a hung plugin) occupy the pool; the next RPC then runs on the connection's Netty event loop and blocks it for the
 * same hour. One such RPC on a connection per event loop blocks every loop: TLS handshakes, upgrades, dataplane
 * frames and replies of all other clients stop.
 */
class ReviewApiRpcPoolTest {

    static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        private final StringBuilder part = new StringBuilder();
        WebSocket ws;

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

    static Map<String, String> basic(String user, String pw) {
        return Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8)));
    }

    static String rpcFrame(String action, long timeoutMs) {
        Map<String, Object> m = new HashMap<>();
        m.put("message_info", Map.of("message_type", "global_agent_msgevent", "message_event_type", "EXEC",
                "dst_region", "no-such-region", "dst_agent", "no-such-agent", "is_rpc", "true", "rpc_timeout_ms", String.valueOf(timeoutMs)));
        m.put("message_payload", Map.of("action", action));
        return new Gson().toJson(m);
    }

    /** {reply, failure}: alice connects and sends one RPC that is answered at once. */
    static String[] alicePing(HttpClient http, URI api) {
        try {
            Client a = new Client();
            WebSocket.Builder b = http.newWebSocketBuilder();
            basic("alice", "alice-password-1").forEach(b::header);
            a.ws = b.buildAsync(api, a).get(8, TimeUnit.SECONDS);
            Map<String, Object> m = new HashMap<>();
            m.put("message_info", Map.of("message_type", "global_controller_msgevent", "message_event_type", "EXEC", "is_rpc", "true"));
            m.put("message_payload", Map.of("action", "ping"));
            a.ws.sendText(new Gson().toJson(m), true).get(5, TimeUnit.SECONDS);
            String reply = a.got.poll(8, TimeUnit.SECONDS);
            a.ws.abort();
            return new String[]{reply, null};
        } catch (Exception e) {
            return new String[]{null, e.toString()};
        }
    }

    @Test
    void oneOrdinaryUserCannotStopEveryoneElsesApiRequests() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-review-rpc");
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
        CredentialStore store = CredentialStore.parse(
                "alice:tenant-a:admin:" + PasswordHash.create("alice-password-1".toCharArray(), r).encode() + "\n"
                        + "bob:tenant-a:user:" + PasswordHash.create("bob-password-22".toCharArray(), r).encode() + "\n", "t");
        Authenticator auth = new Authenticator(() -> store, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 300_000L, System::currentTimeMillis);

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inRpc = new AtomicInteger();
        ApiGateway noReply = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                if ("ping".equals(msg.getParam("action"))) {          // alice's request: answered at once
                    msg.setParam("status", "10");
                    return msg;
                }
                inRpc.incrementAndGet();
                try {
                    release.await(Math.min(timeoutMs, 120_000L), TimeUnit.MILLISECONDS);   // no reply arrives
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, noReply, 2, 64, plugin.getLogger("t", io.cresco.library.utilities.CLogger.Level.Info));
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
            URI api = URI.create("wss://localhost:" + server.boundPort() + WsRouter.APISOCKET_PATH);

            // control: before the attack alice is answered
            String[] before = alicePing(http, api);
            assertNotNull(before[0], "control failed: " + before[1]);

            // bob: one connection per event loop (and some spare), opened one after another so they cover every loop
            int loops = Math.max(1, Integer.getInteger("io.netty.eventLoopThreads", Runtime.getRuntime().availableProcessors() * 2));
            List<Client> bob = new ArrayList<>();
            for (int i = 0; i < loops + 2; i++) {
                Client c = new Client();
                WebSocket.Builder b = http.newWebSocketBuilder();
                basic("bob", "bob-password-22").forEach(b::header);
                c.ws = b.buildAsync(api, c).get(20, TimeUnit.SECONDS);
                bob.add(c);
            }
            // 64 hour-long RPCs fill the pool ...
            for (int i = 0; i < 64; i++) bob.get(0).ws.sendText(rpcFrame("hang", 3_600_000L), true).get(5, TimeUnit.SECONDS);
            long until = System.currentTimeMillis() + 15_000;
            while (inRpc.get() < 64 && System.currentTimeMillis() < until) Thread.sleep(50);
            // ... and one more on each connection runs on (and blocks) that connection's event loop
            for (Client c : bob) c.ws.sendText(rpcFrame("hang", 3_600_000L), true);
            until = System.currentTimeMillis() + 15_000;
            while (inRpc.get() < 64 + bob.size() && System.currentTimeMillis() < until) Thread.sleep(50);

            // after the attack: alice, an admin on her own connection, asks for something that answers at once
            String[] after = alicePing(http, api);
            assertNotNull(after[0], "alice got no answer within seconds while bob (role user) holds " + inRpc.get()
                    + " hour-long RPCs: " + (after[1] != null ? after[1] : "no reply"));
        } finally {
            release.countDown();      // free the JVM-wide pool for the other tests
            server.stop();
        }
    }
}
