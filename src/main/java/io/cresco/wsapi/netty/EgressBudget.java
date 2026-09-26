package io.cresco.wsapi.netty;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded egress backlog for one dataplane WebSocket consumer.
 *
 * <p>The JMS listener that feeds a consumer never blocks: every dataplane listener on an agent shares one
 * pooled JMS session, so waiting on one slow consumer would pace every other stream on the agent (stunnel,
 * other clients) to it. Instead frames queue on the consumer's channel up to a byte budget, far past Netty's
 * write high-water mark (2 MiB, i.e. eight 256 KiB frames, which a burst crosses in a few milliseconds of
 * consumer jitter). Only a consumer more than {@code channelCap} bytes behind, or all consumers together more
 * than {@code globalCap}, is overloaded: its frames are then dropped, and every drop is counted
 * ({@link #DROPPED}, wsapi.dataplane.egress.dropped) and logged by the caller. Both caps bound memory the way
 * the old high-water-mark shedding did (a stalled consumer once grew the outbound buffer without bound).
 *
 * <p>Single-writer per instance (one JMS session thread delivers a listener's messages); {@link #release}
 * runs on the channel's event loop when a write completes or fails.
 */
final class EgressBudget {

    /** Bytes queued to all dataplane WS consumers of this process and not yet written. */
    static final AtomicLong GLOBAL_PENDING = new AtomicLong();
    /** Frames dropped because a consumer (or all of them) exceeded the backlog budget. */
    static final AtomicLong DROPPED = new AtomicLong();
    /** Largest backlog any single consumer reached, in bytes (how close bursts come to the cap). */
    static final AtomicLong MAX_PENDING = new AtomicLong();

    private final AtomicLong pending = new AtomicLong();
    private final long channelCap;
    private final long globalCap;

    EgressBudget(long channelCap, long globalCap) {
        this.channelCap = Math.max(0L, channelCap);
        this.globalCap = Math.max(0L, globalCap);
    }

    /** Default process-wide budget: a quarter of the heap, at most 512 MiB. */
    static long defaultGlobalCap() {
        return Math.min(512L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
    }

    long pending() { return pending.get(); }

    /**
     * Reserves room for a frame of {@code bytes}. A frame always fits an empty backlog, so a frame larger
     * than the cap is still delivered to a consumer that is keeping up.
     * @return true to write the frame (call {@link #release} when the write completes), false to drop it
     */
    boolean tryAcquire(long bytes) {
        long p = pending.get();
        if (p > 0 && p + bytes > channelCap) return false;
        long g = GLOBAL_PENDING.get();
        if (g > 0 && g + bytes > globalCap) return false;
        long now = pending.addAndGet(bytes);
        GLOBAL_PENDING.addAndGet(bytes);
        long m;
        while (now > (m = MAX_PENDING.get()) && !MAX_PENDING.compareAndSet(m, now)) { }
        return true;
    }

    /** The frame's write completed (or failed, e.g. the channel closed): its bytes leave the backlog. */
    void release(long bytes) {
        pending.addAndGet(-bytes);
        GLOBAL_PENDING.addAndGet(-bytes);
    }
}
