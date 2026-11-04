package io.cresco.wsapi.netty;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 3 (review/api-20261104): log-stream configuration now runs on a pool, and a stream that closes
 * turns log streaming off on the agent only when {@code dpEnabled} is already set. A stream that closes while its
 * setlogdp=true is still on its way (a slow agent, or a full pool queue) sends nothing; the configuration then completes
 * and leaves the agent mirroring its log to a session nobody reads, until that agent restarts.
 */
class ReviewApi3LogStreamTest {

    @Test
    void aStreamClosedDuringItsConfigurationTurnsLogStreamingOffAgain() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch agentAnswers = new CountDownLatch(1);
        CountDownLatch configured = new CountDownLatch(2);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) {
                sent.add(msg.getParam("action") + "=" + msg.getParam("setlogdp"));
            }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                sent.add(msg.getParam("action") + "=" + msg.getParam("setlogdp"));
                if ("setlogdp".equals(msg.getParam("action"))) {
                    try {
                        agentAnswers.await(10, TimeUnit.SECONDS);      // the agent answers late
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                msg.setParam("status_code", "7");
                configured.countDown();
                return msg;
            }
        };
        Authenticator auth = new Authenticator(CredentialStore::empty, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 1, 4, plugin.getLogger("review", CLogger.Level.Info));
        EmbeddedChannel ch = new EmbeddedChannel(new LogStreamerWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "alice", "admin"), gate));
        ch.writeInbound(new TextWebSocketFrame("region-1,agent-1,Info,io.cresco"));
        long until = System.currentTimeMillis() + 5_000;
        while (!sent.contains("setlogdp=true") && System.currentTimeMillis() < until) Thread.sleep(20);
        ch.close();                                                  // the client goes away meanwhile
        agentAnswers.countDown();
        assertTrue(configured.await(10, TimeUnit.SECONDS));
        Thread.sleep(500);                                           // let any disable task run
        assertTrue(sent.contains("setlogdp=false"), "the closed stream left log streaming on at the agent: " + sent);
    }
}
