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
 * while the configuration pool is full; it used to fall back to the JVM's common ForkJoin pool.
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
        long until = System.currentTimeMillis() + 5_000;
        while (LogStreamerWsHandler.CONFIG_POOL.getActiveCount() > 0 && System.currentTimeMillis() < until) Thread.sleep(10);

        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> blockers = new ArrayList<>();
        try {
            int capacity = LogStreamerWsHandler.CONFIG_POOL.getMaximumPoolSize() + LogStreamerWsHandler.CONFIG_POOL.getQueue().remainingCapacity();
            for (int i = 0; i < capacity; i++) {
                Runnable r = () -> {
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                LogStreamerWsHandler.CONFIG_POOL.execute(r);
                blockers.add(r);
            }
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> LogStreamerWsHandler.CONFIG_POOL.execute(() -> { }),
                    "control: the configuration pool is full");

            ch.close();
            until = System.currentTimeMillis() + 5_000;
            while (undoThreads.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10);
            assertEquals(1, undoThreads.size(), "the undo ran once");
            assertTrue(undoThreads.get(0).startsWith("wsapi-logstream-undo"), "the undo ran on " + undoThreads.get(0));
        } finally {
            release.countDown();
        }
        assertTrue(LogStreamerWsHandler.UNDO_POOL.getMaximumPoolSize() <= LogStreamerWsHandler.UNDO_THREADS
                && LogStreamerWsHandler.UNDO_POOL.getQueue().remainingCapacity() + LogStreamerWsHandler.UNDO_POOL.getQueue().size()
                == LogStreamerWsHandler.UNDO_QUEUE, "bounded");
    }
}
