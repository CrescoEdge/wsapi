package io.cresco.wsapi.netty;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 2 (review/api-20261104): the fix moved API-socket RPCs off the Netty event loops, but the log
 * stream still sends its setlogdp / setloglevel RPCs from channelRead0 (and setlogdp=false from channelInactive), on the
 * connection's event loop, waiting up to the library's 30 s RPC timeout. A log-stream line naming an agent that does
 * not answer (a typo, an agent that is down) blocks that loop; one line per loop stops every client of wsapi.
 */
class ReviewApi2LogStreamLoopTest {

    @Test
    void logStreamConfigurationNeverBlocksOtherClients() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-review-ls");
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
        AtomicInteger hung = new AtomicInteger();
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                String action = msg.getParam("action");
                if ("setlogdp".equals(action) || "setloglevel".equals(action)) {     // the named agent does not answer
                    hung.incrementAndGet();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }
                msg.setParam("status", "10");
                return msg;
            }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 2, 64, plugin.getLogger("t", io.cresco.library.utilities.CLogger.Level.Info));
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
            URI logs = URI.create("wss://localhost:" + server.boundPort() + WsRouter.LOGSTREAMER_PATH);

            String[] before = bobPing(http, api);
            assertNotNull(before[0], "control failed: " + before[1]);

            // alice (admin) opens one log stream per event loop and points each at an agent that does not answer
            int loops = Math.max(1, Integer.getInteger("io.netty.eventLoopThreads", Runtime.getRuntime().availableProcessors() * 2));
            List<WebSocket> streams = new ArrayList<>();
            for (int i = 0; i < loops + 2; i++) {
                WebSocket.Builder b = http.newWebSocketBuilder();
                ReviewApiRpcPoolTest.basic("alice", "alice-password-1").forEach(b::header);
                streams.add(b.buildAsync(logs, new ReviewApiRpcPoolTest.Client()).get(20, TimeUnit.SECONDS));
            }
            for (WebSocket s : streams) s.sendText("region-1,agent-typo,Info,io.cresco", true);
            long until = System.currentTimeMillis() + 10_000;
            while (hung.get() < streams.size() && System.currentTimeMillis() < until) Thread.sleep(50);

            String[] after = bobPing(http, api);
            assertNotNull(after[0], "bob got no answer while " + hung.get() + " log-stream RPCs wait on event loops: "
                    + (after[1] != null ? after[1] : "no reply"));
        } finally {
            release.countDown();
            server.stop();
        }
    }

    static String[] bobPing(HttpClient http, URI api) {
        try {
            ReviewApiRpcPoolTest.Client c = new ReviewApiRpcPoolTest.Client();
            WebSocket.Builder b = http.newWebSocketBuilder();
            ReviewApiRpcPoolTest.basic("bob", "bob-password-22").forEach(b::header);
            c.ws = b.buildAsync(api, c).get(8, TimeUnit.SECONDS);
            Map<String, Object> m = new HashMap<>();
            m.put("message_info", Map.of("message_type", "agent_msgevent", "message_event_type", "EXEC", "is_rpc", "true"));
            m.put("message_payload", Map.of("action", "ping"));
            c.ws.sendText(new com.google.gson.Gson().toJson(m), true).get(5, TimeUnit.SECONDS);
            String reply = c.got.poll(8, TimeUnit.SECONDS);
            c.ws.abort();
            return new String[]{reply, null};
        } catch (Exception e) {
            return new String[]{null, e.toString()};
        }
    }
}
