package io.cresco.wsapi.netty;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The log streams' worker pools, one set per wsapi server ({@link WsRouter.Gate}), shut down with it. They used to be
 * JVM-wide statics: a stopped or restarted wsapi left its tasks and threads behind, and one server's (or test's) queued
 * work counted against another's.
 *
 * <ul>
 *   <li>{@link #config}: log-stream configuration, never on a Netty event loop: its RPCs wait for the named agent (up to
 *       {@link LogStreamerWsHandler#CONFIG_RPC_TIMEOUT_MS}), and a line naming an agent that does not answer used to hold
 *       the connection's event loop, and every other client on it, for the library's 30 s RPC timeout. Bounded:
 *       {@value #CONFIG_THREADS} threads, {@value #CONFIG_QUEUE} queued; beyond that a client is told to retry. Each
 *       admin holds at most {@link LogStreamerWsHandler#POOL_SHARE_PER_ADMIN} of it ({@link #inPool}).</li>
 *   <li>{@link #undo}: turning log streaming off again for a closed stream ({@code setlogdp=false} at every agent it
 *       turned it on at), never on the configuration pool's callers' threads nor the common ForkJoin pool: each undo RPC
 *       may wait up to 10 s, and the common pool is shared by the whole JVM and sized for computation. Bounded:
 *       {@value #UNDO_THREADS} threads and {@value #UNDO_QUEUE} closed streams waiting (each stream queues one undo, at
 *       most once); beyond that an undo is not run, and an ERROR names the agents left streaming ({@link #undoDropped}).</li>
 * </ul>
 */
final class LogStreamPools {

    static final int CONFIG_THREADS = 8;
    static final int CONFIG_QUEUE = 64;
    static final int UNDO_THREADS = 4;
    static final int UNDO_QUEUE = 4096;

    final ThreadPoolExecutor config = pool(CONFIG_THREADS, CONFIG_QUEUE, "wsapi-logstream-config");
    final ThreadPoolExecutor undo = pool(UNDO_THREADS, UNDO_QUEUE, "wsapi-logstream-undo");
    /** Configuration tasks each admin holds on {@link #config}, across its streams. */
    final ConcurrentHashMap<String, AtomicInteger> inPool = new ConcurrentHashMap<>();
    /** Undos that could not be queued (the undo pool was full). */
    final AtomicLong undoDropped = new AtomicLong();

    private static ThreadPoolExecutor pool(int threads, int queue, String name) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** The server stops: configurations in flight are interrupted; queued undos still run (their RPCs end on their own). */
    void shutdown() {
        config.shutdownNow();
        undo.shutdown();
    }
}
