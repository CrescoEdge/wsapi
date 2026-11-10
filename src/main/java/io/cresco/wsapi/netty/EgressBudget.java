package io.cresco.wsapi.netty;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded egress backlog for one dataplane WebSocket consumer.
 *
 * <p>Each consumer's JMS listener has a JMS session of its own (with one shared session, waiting on one slow consumer
 * would pace every other stream on the agent -- stunnel, other clients -- to it). Frames queue on the consumer's
 * channel up to a byte budget, far past Netty's
 * write high-water mark (2 MiB, i.e. eight 256 KiB frames, which a burst crosses in a few milliseconds of
 * consumer jitter). A consumer more than {@code channelCap} bytes behind is overloaded: its listener waits
 * ({@link #add}, {@link #over}, {@link #drained}) on a JMS session of its own, so the rest of its stream waits at the
 * broker, which slows the stream's writers -- backpressure, nothing lost. With backpressure off its frames are dropped
 * past the cap instead ({@link #tryAcquire}), every drop counted ({@link #DROPPED}, wsapi.dataplane.egress.dropped) and
 * logged by the caller. Both caps bound memory the way the old high-water-mark shedding did (a stalled consumer once
 * grew the outbound buffer without bound).
 *
 * <p>The process-wide budget ({@code globalCap}) is shared by the consumers that have a backlog, in equal parts
 * ({@link #share}). While all consumers together are past it, a listener waits only if its own consumer holds more than
 * its share (at least one does), and only for its own backlog: a consumer that keeps up is never held because others
 * do not read. (When {@code over()} and {@code drained()} counted the process-wide backlog itself, every consumer's
 * listener waited while a few that did not read held the budget, and a consumer with nothing queued was never closed
 * as stalled either.) Consumers that were within their share when the budget was reached may still fill it, so the
 * process can hold up to twice the budget until those over their share have read or are closed as stalled. With
 * backpressure off nothing waits: past the process-wide budget a frame is dropped, whoever it is for ({@link #tryAcquire}).
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

    /** Consumers of this process with a backlog now: the process-wide budget is shared among them. */
    static final java.util.concurrent.atomic.AtomicInteger BACKLOGGED = new java.util.concurrent.atomic.AtomicInteger();

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

    /** This consumer's part of the process-wide budget: an equal part for every consumer that has a backlog. */
    long share() {
        return globalCap / Math.max(1, BACKLOGGED.get());
    }

    /** {@code bytes} joined this consumer's backlog, which is now {@code now}. */
    private void queued(long bytes, long now) {
        GLOBAL_PENDING.addAndGet(bytes);
        if (bytes > 0 && now == bytes) BACKLOGGED.incrementAndGet();     // its backlog began
        long m;
        while (now > (m = MAX_PENDING.get()) && !MAX_PENDING.compareAndSet(m, now)) { }
    }

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
        queued(bytes, pending.addAndGet(bytes));
        return true;
    }

    /**
     * Backpressure: the frame always queues; the caller's listener waits when {@link #over} until {@link #drained}. The
     * backlog then stays within the cap plus one frame.
     */
    void add(long bytes) {
        queued(bytes, pending.addAndGet(bytes));
    }

    /**
     * This consumer is more than its cap behind, or holds more than its share of a process-wide budget that all consumers
     * together are past: its listener waits.
     */
    boolean over() {
        long p = pending.get();
        return p > channelCap || (p > share() && GLOBAL_PENDING.get() > globalCap);
    }

    /**
     * Back to half its cap and, unless the process is under 90% of its budget, to half its share of it: a waiting
     * listener goes on. Its own backlog decides, never someone else's.
     */
    boolean drained() {
        long p = pending.get();
        return p <= channelCap / 2 && (p <= share() / 2 || GLOBAL_PENDING.get() <= globalCap - globalCap / 10);
    }

    /** The frame's write completed (or failed, e.g. the channel closed): its bytes leave the backlog. */
    void release(long bytes) {
        long now = pending.addAndGet(-bytes);
        GLOBAL_PENDING.addAndGet(-bytes);
        if (bytes > 0 && now == 0) BACKLOGGED.decrementAndGet();         // its backlog is written
    }
}
