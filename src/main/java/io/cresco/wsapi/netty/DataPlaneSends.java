package io.cresco.wsapi.netty;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;

import java.util.concurrent.TimeUnit;

/**
 * The threads that publish dataplane client frames to the broker, off the Netty event loops. A send can wait: the
 * dataplane's topics have producer flow control (a topic whose subscribers are behind slows its writers), and a wait on
 * an event loop would stall every other connection that loop serves (API calls, logins, other streams). Each dataplane
 * connection takes one of these threads for all its sends, so its frames reach the broker in the order it sent them.
 * Shared by every connection of the process; made on first use and stopped with the server.
 */
final class DataPlaneSends {

    private static EventExecutorGroup group;
    private static int groupThreads;

    private DataPlaneSends() { }

    /** Default: one per core, at least 2 and at most 32. */
    static int defaultThreads() {
        return Math.max(2, Math.min(32, Runtime.getRuntime().availableProcessors()));
    }

    /** One of the send threads (made on first use, {@code threads} of them); null when none can be had. */
    static synchronized EventExecutor next(int threads) {
        if (threads <= 0) return null;
        if (group == null || group.isShuttingDown()) {
            group = new DefaultEventExecutorGroup(threads, r -> {
                Thread t = new Thread(r, "wsapi-dataplane-send");
                t.setDaemon(true);
                return t;
            });
            groupThreads = threads;
        }
        return group.next();
    }

    static synchronized int threads() { return group == null ? 0 : groupThreads; }

    /** Stop the send threads (the server is stopping); what is queued is still published, for at most 5 s. */
    static synchronized void shutdown() {
        EventExecutorGroup g = group;
        group = null;
        if (g != null) g.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }
}
