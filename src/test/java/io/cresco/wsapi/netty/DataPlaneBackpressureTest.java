package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.data.DataPlaneService;
import io.cresco.library.data.PacedListener;
import io.cresco.library.plugin.PluginBuilder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import org.apache.activemq.command.ActiveMQBytesMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dataplane backpressure in wsapi: a WebSocket consumer that reads slower than its stream arrives makes its listener WAIT
 * (nothing dropped, order kept) and a consumer that reads nothing is closed; client frames are published off the event
 * loop, in order, and reading pauses while too many wait to be published.
 */
class DataPlaneBackpressureTest {

    static final int KIB = 1024, FRAME = 256 * KIB;

    /** A dataplane that records the listener wsapi subscribes and what it publishes (optionally holding every send). */
    static final class Dataplane {
        final AtomicReference<MessageListener> listener = new AtomicReference<>();
        final List<Integer> sent = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch holdSends = new CountDownLatch(0);

        DataPlaneService proxy() {
            return (DataPlaneService) Proxy.newProxyInstance(DataPlaneService.class.getClassLoader(), new Class[]{DataPlaneService.class},
                    (p, m, a) -> {
                        switch (m.getName()) {
                            case "addMessageListener": listener.set((MessageListener) a[1]); return "listener-1";
                            case "createTextMessage": return new ActiveMQTextMessage();
                            case "createBytesMessage": return new ActiveMQBytesMessage();
                            case "sendMessage":
                                holdSends.await(30, TimeUnit.SECONDS);
                                for (Object o : a) {
                                    if (o instanceof ActiveMQBytesMessage) {
                                        ActiveMQBytesMessage bm = (ActiveMQBytesMessage) o;
                                        bm.reset();
                                        sent.add(bm.readInt());
                                    }
                                }
                                return true;
                            case "shardFor": return 0;
                            case "getDataPlaneShardCount": return 1;
                            case "hashCode": return 11;
                            case "equals": return p == a[0];
                            case "toString": return "BackpressureDataplane";
                            default: return m.getReturnType() == boolean.class ? false : null;
                        }
                    });
        }
    }

    /** Holds every outbound write until released: a WebSocket client that has not read yet. */
    static final class SlowClient extends ChannelOutboundHandlerAdapter {
        final List<Object> written = Collections.synchronizedList(new ArrayList<>());
        final List<ChannelPromise> held = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            written.add(msg);
            held.add(promise);
        }

        /** The client reads {@code n} more frames. */
        void read(int n) {
            for (int i = 0; i < n; i++) {
                ChannelPromise p;
                synchronized (held) {
                    if (held.isEmpty()) return;
                    p = held.remove(0);
                }
                p.setSuccess();
            }
        }
    }

    static EmbeddedChannel consumer(PluginBuilder plugin, SlowClient client) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(client, new DataPlaneWsHandler(plugin,
                ReviewApiDataplaneTest.principal("tenant-a", "root", "admin"), ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(Map.of("ident_key", "stream_name", "ident_id", "bp-1"))));
        client.read(1);                                                  // the activation reply
        assertEquals("10", ((Map<?, ?>) new Gson().fromJson(((TextWebSocketFrame) client.written.get(0)).text(), Map.class))
                .get("status_code"));
        return ch;
    }

    static Message frame(int seq) throws Exception {
        ActiveMQBytesMessage m = new ActiveMQBytesMessage();
        byte[] body = new byte[FRAME];
        ByteBuffer.wrap(body).putInt(seq);
        m.writeBytes(body);
        m.reset();                                                       // as received: read-only, readable
        return m;
    }

    @Test
    void aSlowConsumerHoldsItsListenerInsteadOfLosingFrames() throws Exception {
        Dataplane dp = new Dataplane();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(dp.proxy(), Map.of("wsapi_dataplane_egress_buffer_bytes", String.valueOf(1024 * KIB)));
        SlowClient client = new SlowClient();
        EmbeddedChannel ch = consumer(plugin, client);
        MessageListener ml = dp.listener.get();
        assertTrue(ml instanceof PacedListener, "a dataplane consumer's listener is paced: the controller gives it its own session");
        long droppedBefore = DataPlaneWsHandler.EGRESS_DROPPED.get();

        AtomicInteger delivered = new AtomicInteger();
        Thread session = new Thread(() -> {
            try {
                for (int i = 0; i < 12; i++) {
                    ml.onMessage(frame(i));
                    delivered.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "jms-session");
        session.start();

        // 1 MiB budget, 256 KiB frames: the 5th frame takes the backlog past it, and its listener waits there
        long until = System.currentTimeMillis() + 5000;
        while (client.written.size() < 6 && System.currentTimeMillis() < until) Thread.sleep(10);
        Thread.sleep(300);
        assertEquals(6, client.written.size(), "activation reply + 5 frames queued, then the listener waits");
        assertEquals(4, delivered.get(), "the 5th onMessage has not returned: its stream waits at the broker");

        client.read(2);                                                  // 3 left = 768 KiB: still over half the budget
        Thread.sleep(200);
        assertEquals(4, delivered.get(), "still waiting until the backlog is back to half the budget");
        client.read(1);                                                  // 512 KiB: drained enough
        until = System.currentTimeMillis() + 5000;
        while (delivered.get() < 5 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue(delivered.get() >= 5, "the listener goes on once its consumer has read");

        // the client keeps reading: everything arrives, in order, nothing dropped
        until = System.currentTimeMillis() + 10000;
        while (session.isAlive() && System.currentTimeMillis() < until) {
            client.read(1);
            Thread.sleep(5);
        }
        client.read(100);
        session.join(5000);
        assertFalse(session.isAlive());
        List<Integer> seqs = new ArrayList<>();
        for (Object o : client.written) {
            if (o instanceof BinaryWebSocketFrame) seqs.add(((BinaryWebSocketFrame) o).content().getInt(0));
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) expected.add(i);
        assertEquals(expected, seqs, "every frame, in order");
        assertEquals(droppedBefore, DataPlaneWsHandler.EGRESS_DROPPED.get(), "nothing dropped");
        assertTrue(DataPlaneWsHandler.EGRESS_PAUSES.get() > 0);
        ch.finishAndReleaseAll();
    }

    @Test
    void aConsumerThatReadsNothingIsClosedAndReleasesItsStream() throws Exception {
        Dataplane dp = new Dataplane();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(dp.proxy(), Map.of("wsapi_dataplane_egress_buffer_bytes",
                String.valueOf(512 * KIB), "wsapi_dataplane_stall_close_ms", "1000"));
        SlowClient client = new SlowClient();
        EmbeddedChannel ch = consumer(plugin, client);
        MessageListener ml = dp.listener.get();
        long stalledBefore = DataPlaneWsHandler.EGRESS_STALLED.get();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) ml.onMessage(frame(i));             // the 3rd takes it past 512 KiB: waits, 1 s, closed
        long waited = System.currentTimeMillis() - t0;
        assertTrue(waited >= 1000 && waited < 5000, "the listener gave up after the stall time, not before, not never: " + waited);
        assertEquals(stalledBefore + 1, DataPlaneWsHandler.EGRESS_STALLED.get());
        CloseWebSocketFrame close = null;
        for (Object o : client.written) if (o instanceof CloseWebSocketFrame) close = (CloseWebSocketFrame) o;
        assertNotNull(close, "the client is told why: a close frame");
        assertEquals(1013, close.statusCode());
        ch.finishAndReleaseAll();
    }

    /** Completes every write at once: a WebSocket client that reads as fast as it is sent to. */
    static final class FastClient extends ChannelOutboundHandlerAdapter {
        final AtomicInteger frames = new AtomicInteger();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            frames.incrementAndGet();
            io.netty.util.ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    }

    @Test
    void aConsumerThatReadsATrickleIsClosedLikeOneThatReadsNothing() throws Exception {
        Dataplane dp = new Dataplane();
        // 16 MiB budget, 1 s stall time: held past its budget, a consumer has to read half of it (8 MiB) in that second
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(dp.proxy(), Map.of("wsapi_dataplane_egress_buffer_bytes",
                String.valueOf(64 * FRAME), "wsapi_dataplane_stall_close_ms", "1000"));
        SlowClient client = new SlowClient();
        EmbeddedChannel ch = consumer(plugin, client);
        MessageListener ml = dp.listener.get();
        long stalledBefore = DataPlaneWsHandler.EGRESS_STALLED.get();
        for (int i = 0; i < 64; i++) ml.onMessage(frame(i));            // the whole budget, not past it: no wait yet

        java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread trickle = new Thread(() -> {                              // one 256 KiB frame every 300 ms: never "nothing"
            try {
                while (reading.get()) {
                    Thread.sleep(300);
                    client.read(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "trickle-reader");
        trickle.setDaemon(true);
        trickle.start();
        try {
            long t0 = System.currentTimeMillis();
            ml.onMessage(frame(64));                                     // past the budget: its stream is held at the broker
            long waited = System.currentTimeMillis() - t0;
            assertTrue(waited >= 1000 && waited < 5000, "held for the stall time, then given up on (a reader of 256 KiB every"
                    + " 300 ms would hold its stream's writers for 10 s here, and for ever at one frame per stall time): " + waited);
            assertEquals(stalledBefore + 1, DataPlaneWsHandler.EGRESS_STALLED.get());
            CloseWebSocketFrame close = null;
            for (Object o : client.written) if (o instanceof CloseWebSocketFrame) close = (CloseWebSocketFrame) o;
            assertNotNull(close, "the client is told why: a close frame");
            assertEquals(1013, close.statusCode());
        } finally {
            reading.set(false);
            trickle.join(2000);
            client.read(1000);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void aConsumerThatKeepsUpNeverWaitsForAnotherConsumersBacklog() throws Exception {
        Dataplane dp = new Dataplane();
        // the process-wide budget: 1 MiB more than is queued in this JVM now
        long processBudget = EgressBudget.GLOBAL_PENDING.get() + 1024 * KIB;
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(dp.proxy(), Map.of("wsapi_dataplane_egress_global_bytes",
                String.valueOf(processBudget), "wsapi_dataplane_stall_close_ms", "1000"));
        SlowClient stuck = new SlowClient();
        EmbeddedChannel slow = consumer(plugin, stuck);
        MessageListener toSlow = dp.listener.get();
        FastClient reader = new FastClient();
        EmbeddedChannel fast = new EmbeddedChannel(reader, new DataPlaneWsHandler(plugin,
                ReviewApiDataplaneTest.principal("tenant-a", "root", "admin"), ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        fast.writeInbound(new TextWebSocketFrame(new Gson().toJson(Map.of("ident_key", "stream_name", "ident_id", "bp-2"))));
        MessageListener toFast = dp.listener.get();
        assertNotSame(toSlow, toFast, "two consumers, each with its own listener");

        Thread slowSession = new Thread(() -> {                          // a consumer that reads nothing takes 1.25 MiB
            try {
                for (int i = 0; i < 5; i++) toSlow.onMessage(frame(i));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "jms-session-slow");
        slowSession.setDaemon(true);
        slowSession.start();
        long until = System.currentTimeMillis() + 5000;
        while (stuck.written.size() < 6 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue(EgressBudget.GLOBAL_PENDING.get() > processBudget, "the process-wide budget is spent, by the consumer that does not read");

        AtomicInteger delivered = new AtomicInteger();
        Thread fastSession = new Thread(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    toFast.onMessage(frame(i));
                    delivered.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "jms-session-fast");
        fastSession.setDaemon(true);
        fastSession.start();
        try {
            fastSession.join(3000);
            assertEquals(20, delivered.get(), "the consumer that reads everything at once got " + delivered.get() + " of 20 frames in 3 s:"
                    + " it waits for no backlog but its own");
            assertEquals(21, reader.frames.get(), "the activation reply and every frame");
        } finally {
            stuck.read(1000);
            slowSession.join(5000);
            slow.finishAndReleaseAll();
            fast.finishAndReleaseAll();
        }
    }

    @Test
    void clientFramesArePublishedOffTheEventLoopInOrderAndReadingPausesWhileTheyWait() throws Exception {
        Dataplane dp = new Dataplane();
        dp.holdSends = new CountDownLatch(1);                           // the topic is full: every send waits
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(dp.proxy(), Map.of("wsapi_dataplane_send_threads", "2",
                "wsapi_dataplane_ingress_queue_bytes", String.valueOf(1024 * KIB)));
        SlowClient client = new SlowClient();
        EmbeddedChannel ch = consumer(plugin, client);

        for (int i = 0; i < 8; i++) {
            byte[] b = new byte[FRAME];
            ByteBuffer.wrap(b).putInt(i);
            ch.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b)));   // returns: the event loop never waits
        }
        assertFalse(ch.config().isAutoRead(), "2 MiB wait to be published (> 1 MiB): reading pauses, TCP holds the client");
        dp.holdSends.countDown();                                        // the topic drains
        long until = System.currentTimeMillis() + 5000;
        while (dp.sent.size() < 8 && System.currentTimeMillis() < until) Thread.sleep(10);
        ch.runPendingTasks();
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), dp.sent, "published in the order the client sent them");
        assertTrue(ch.config().isAutoRead(), "reading resumes once they are published");
        ch.finishAndReleaseAll();
    }
}
