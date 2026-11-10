package io.cresco.wsapi.netty;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;

import java.util.concurrent.TimeUnit;

/**
 * The threads that publish dataplane client frames to the broker, off the Netty event loops. A send can wait: the
 * dataplane's topics have producer flow control (a topic whose subscribers are behind slows its writers; the broker
 * holds the sending thread while the topic is full), and a wait on an event loop would stall every other connection
 * that loop serves (API calls, logins, other streams). Each dataplane connection takes one of these threads for all
 * its sends, so its frames reach the broker in the order it sent them.
 *
 * <p>The threads belong to a dataplane shard: flow control is per topic, and each shard is a topic of its own
 * ({@code dataplane_shards}), so a connection shares its send thread only with connections that publish to the same
 * topic, which wait when it does anyway. (Shared by all connections alike, a thread held by one full topic also held the
 * connections of topics with room.) Without sharding there is one topic, and all the threads are its.
 * Made on first use and stopped with the server.
 */
final class DataPlaneSends {

    /** Each shard's send threads. */
    private static final java.util.Map<Integer, EventExecutorGroup> groups = new java.util.HashMap<>();

    private DataPlaneSends() { }

    /** Default: one per core, at least 2 and at most 32. */
    static int defaultThreads() {
        return Math.max(2, Math.min(32, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * One of the send threads of {@code shard} (made on first use: {@code threads} shared out equally among the
     * {@code shards} shards, at least one each); null when none can be had.
     */
    static synchronized EventExecutor next(int threads, int shard, int shards) {
        if (threads <= 0) return null;
        int n = Math.max(1, shards);
        Integer key = Math.floorMod(shard, n);
        EventExecutorGroup group = groups.get(key);
        if (group == null || group.isShuttingDown()) {
            String name = n > 1 ? "wsapi-dataplane-send-shard" + key : "wsapi-dataplane-send";
            group = new DefaultEventExecutorGroup(Math.max(1, threads / n), r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
            groups.put(key, group);
        }
        return group.next();
    }

    /** Stop the send threads (the server is stopping); what is queued is still published, for at most 5 s. */
    static synchronized void shutdown() {
        for (EventExecutorGroup g : groups.values()) g.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        groups.clear();
    }
}
