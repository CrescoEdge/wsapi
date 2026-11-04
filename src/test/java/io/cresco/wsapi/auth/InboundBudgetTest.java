package io.cresco.wsapi.auth;

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
import java.nio.ByteBuffer;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inbound message bytes wsapi holds are bounded per caller (all its connections together) and in total: a connection
 * whose bytes would exceed either bound is closed with 1013 and a reason naming the setting, and the bytes of a message are
 * given back once it has been handled. One user with 30 connections each sending a 60 MiB frame used to hold 1.8 GB.
 */
class InboundBudgetTest {

    static final int MIB = 1 << 20;

    static final class Client implements WebSocket.Listener {
        volatile int closedWith;
        volatile String reason;

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String r) {
            reason = r;
            closedWith = code;
            return null;
        }
    }

    static boolean within(long ms, BooleanSupplier p) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (p.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return p.getAsBoolean();
    }

    @Test
    void aCallerAndEveryoneHoldAtMostTheirBoundOfInboundBytes() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-inbound");
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
        CredentialStore store = CredentialStore.empty();
        for (String u : new String[]{"alice", "bob", "carol"}) {
            store = store.with(new CredentialStore.Entry(u, "tenant-a", "user", PasswordHash.create((u + "-Password-k3v9").toCharArray(), r, 1000)));
        }
        CredentialStore users = store;
        Authenticator auth = new Authenticator(() -> users, () -> null, () -> false, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 300_000L, System::currentTimeMillis);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) { return msg; }
        };
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 2, 16, TestSupport.logger(log)).inboundBounds(8L * MIB, 12L * MIB);
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
            class Conn {
                final Client c = new Client();
                final WebSocket ws;

                Conn(String user) throws Exception {
                    String b = "Basic " + Base64.getEncoder().encodeToString((user + ":" + user + "-Password-k3v9").getBytes());
                    ws = http.newWebSocketBuilder().header("Authorization", b).buildAsync(api, c).get(10, TimeUnit.SECONDS);
                }

                /** The first part of a message, never finished: the server holds it. */
                void part(int bytes) {
                    ws.sendBinary(ByteBuffer.wrap(new byte[bytes]), false);
                }

                void finish() throws Exception {
                    ws.sendBinary(ByteBuffer.allocate(0), true).get(10, TimeUnit.SECONDS);
                }
            }

            // per caller: bob's third 3 MiB part takes him over 8 MiB
            Conn b1 = new Conn("bob"), b2 = new Conn("bob"), b3 = new Conn("bob");
            b1.part(3 * MIB);
            b2.part(3 * MIB);
            assertTrue(within(30_000, () -> gate.inbound().inflight() >= 6L * MIB), "control: 6 MiB held, " + gate.inbound().inflight());
            b3.part(3 * MIB);
            assertTrue(within(30_000, () -> b3.c.closedWith == 1013), "bob's third connection over his bound: " + b3.c.closedWith
                    + " (held: " + gate.inbound().inflight() + ")");
            assertTrue(b3.c.reason.contains("wsapi_max_inflight_bytes_per_user"), b3.c.reason);
            assertEquals(0, b1.c.closedWith + b2.c.closedWith, "his other connections stay open");
            assertTrue(gate.inbound().inflight() <= 6L * MIB + 256 * 1024, "nothing of the refused part is held: " + gate.inbound().inflight());

            // a finished message gives its bytes back
            b1.finish();
            b2.finish();
            assertTrue(within(30_000, () -> gate.inbound().inflight() == 0), "handled messages are given back: " + gate.inbound().inflight());
            Conn b4 = new Conn("bob");
            b4.part(3 * MIB);
            assertTrue(within(30_000, () -> gate.inbound().inflight() >= 3L * MIB), "bob may send again");

            // in total: bob 3 + alice 5 + carol 5 MiB is over 12 MiB; each of them is within 8
            Conn a = new Conn("alice");
            a.part(5 * MIB);
            assertTrue(within(30_000, () -> gate.inbound().inflight() >= 8L * MIB), "control: 8 MiB held");
            Conn c = new Conn("carol");
            c.part(5 * MIB);
            assertTrue(within(30_000, () -> c.c.closedWith == 1013), "carol's connection over the total bound: " + c.c.closedWith
                    + " (held: " + gate.inbound().inflight() + ")");
            assertTrue(c.c.reason.contains("wsapi_max_inflight_bytes="), c.c.reason);
            assertEquals(0, a.c.closedWith + b4.c.closedWith, "the others stay open");

            // closed connections give back what they held
            a.ws.abort();
            b4.ws.abort();
            assertTrue(within(30_000, () -> gate.inbound().inflight() == 0), "after the connections close: " + gate.inbound().inflight());
        } finally {
            server.stop();
        }
    }
}
