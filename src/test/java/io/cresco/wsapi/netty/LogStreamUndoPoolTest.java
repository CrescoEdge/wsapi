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
 * A closed log stream's undo (setlogdp=false, a blocking RPC of up to 10 s) runs on wsapi's own bounded undo pool, also
 * while the configuration pool is full; it used to fall back to the JVM's common ForkJoin pool. The pools are the gate's
 * own (one set per server), so tasks another test (or server) left queued cannot fill them: this test used to fail now and
 * then when an earlier test had left the JVM-wide configuration pool's queue nearly full.
 */
class LogStreamUndoPoolTest {

    @Test
    void theUndoRunsOnItsOwnPoolWhenTheConfigurationPoolIsFull() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<String> undoThreads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch configured = new CountDownLatch(2);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                if ("false".equals(msg.getParam("setlogdp"))) undoThreads.add(Thread.currentThread().getName());
                msg.setParam("status_code", "7");
                configured.countDown();
                return msg;
            }
        };
        Authenticator auth = new Authenticator(CredentialStore::empty, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        WsRouter.Gate gate = new WsRouter.Gate(auth, gw, 1, 4, plugin.getLogger("t", CLogger.Level.Info));
        EmbeddedChannel ch = new EmbeddedChannel(new LogStreamerWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "alice", "admin"), gate));
        ch.writeInbound(new TextWebSocketFrame("region-1,agent-1,Info,io.cresco"));
        assertTrue(configured.await(10, TimeUnit.SECONDS), "control: configured (setlogdp=true, setloglevel)");
        // this gate's own pools (one set per server): no other test's queued work is in them
        LogStreamPools pools = gate.logStreams;
        long until = System.currentTimeMillis() + 5_000;
        while ((pools.config.getActiveCount() > 0 || !pools.config.getQueue().isEmpty()) && System.currentTimeMillis() < until) Thread.sleep(10);

        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> blockers = new ArrayList<>();
        try {
            // fill it until it refuses (a computed capacity raced with an idle worker picking up its first task)
            boolean full = false;
            for (int i = 0; i < 1_000 && !full; i++) {
                Runnable r = () -> {
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                try {
                    pools.config.execute(r);
                    blockers.add(r);
                } catch (java.util.concurrent.RejectedExecutionException refused) {
                    full = true;
                }
            }
            assertTrue(full && blockers.size() >= LogStreamPools.CONFIG_THREADS + LogStreamPools.CONFIG_QUEUE - 1,
                    "control: the configuration pool is full (" + blockers.size() + " blocked)");

            ch.close();
            until = System.currentTimeMillis() + 5_000;
            while (undoThreads.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10);
            assertEquals(1, undoThreads.size(), "the undo ran once");
            assertTrue(undoThreads.get(0).startsWith("wsapi-logstream-undo"), "the undo ran on " + undoThreads.get(0));
        } finally {
            release.countDown();
        }
        assertTrue(pools.undo.getMaximumPoolSize() <= LogStreamPools.UNDO_THREADS
                && pools.undo.getQueue().remainingCapacity() + pools.undo.getQueue().size() == LogStreamPools.UNDO_QUEUE, "bounded");
        gate.shutdown();
        assertTrue(pools.config.isShutdown() && pools.undo.isShutdown(), "the gate's pools stop with it");
        WsRouter.Gate other = new WsRouter.Gate(auth, gw, 1, 4, plugin.getLogger("t", CLogger.Level.Info));
        assertNotSame(pools, other.logStreams, "each server has its own pools");
        other.shutdown();
    }
}
