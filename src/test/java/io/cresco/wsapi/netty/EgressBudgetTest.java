package io.cresco.wsapi.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Dataplane WS egress backlog: bursts queue within the budget; only an overloaded consumer drops. */
class EgressBudgetTest {

    static final long KIB = 1024, MIB = 1024 * KIB, FRAME = 256 * KIB;

    @Test
    void aBurstFarPastNettysHighWaterMarkIsQueuedNotDropped() {
        // the loss seen in inter-node runs: 800 x 256 KiB, the consumer a few MiB behind at the peak
        EgressBudget b = new EgressBudget(32 * MIB, 512 * MIB);
        long before = EgressBudget.GLOBAL_PENDING.get();
        for (int i = 0; i < 40; i++) assertTrue(b.tryAcquire(FRAME), "frame " + i + " (10 MiB queued is within budget)");
        assertEquals(40 * FRAME, b.pending());
        assertTrue(EgressBudget.MAX_PENDING.get() >= 40 * FRAME, "the peak backlog is recorded");
        for (int i = 0; i < 40; i++) b.release(FRAME);
        assertEquals(0, b.pending());
        assertEquals(before, EgressBudget.GLOBAL_PENDING.get(), "every byte is released when its write completes");
    }

    @Test
    void aConsumerMoreThanItsBudgetBehindHasFramesDroppedUntilItDrains() {
        EgressBudget b = new EgressBudget(4 * MIB, 512 * MIB);
        for (int i = 0; i < 16; i++) assertTrue(b.tryAcquire(FRAME));
        assertFalse(b.tryAcquire(FRAME), "the 17th frame would exceed 4 MiB: overloaded, drop");
        assertFalse(b.tryAcquire(1), "still full");
        b.release(FRAME);                      // one write completes
        assertTrue(b.tryAcquire(FRAME), "room again after the consumer drains a frame");
        for (int i = 0; i < 16; i++) b.release(FRAME);
        assertEquals(0, b.pending());
    }

    @Test
    void aFrameLargerThanTheBudgetStillReachesAConsumerThatIsKeepingUp() {
        EgressBudget b = new EgressBudget(1 * MIB, 512 * MIB);
        assertTrue(b.tryAcquire(8 * MIB), "an empty backlog takes any one frame");
        assertFalse(b.tryAcquire(1), "but nothing more until it is written");
        b.release(8 * MIB);
        assertTrue(b.tryAcquire(FRAME));
        b.release(FRAME);
    }

    @Test
    void allConsumersTogetherAreBoundedByTheProcessBudget() {
        long base = EgressBudget.GLOBAL_PENDING.get();
        long global = base + 3 * MIB;          // room for exactly 3 MiB more across consumers in this process
        EgressBudget a = new EgressBudget(32 * MIB, global), c = new EgressBudget(32 * MIB, global);
        for (int i = 0; i < 8; i++) assertTrue(a.tryAcquire(FRAME));      // 2 MiB
        for (int i = 0; i < 4; i++) assertTrue(c.tryAcquire(FRAME));      // 3 MiB total
        assertFalse(c.tryAcquire(FRAME), "the process budget is spent");
        assertFalse(a.tryAcquire(FRAME));
        a.release(FRAME);
        assertTrue(c.tryAcquire(FRAME), "freed by another consumer's write");
        for (int i = 0; i < 7; i++) a.release(FRAME);
        for (int i = 0; i < 5; i++) c.release(FRAME);
        assertEquals(base, EgressBudget.GLOBAL_PENDING.get());
    }

    @Test
    void theDefaultProcessBudgetIsAQuarterOfTheHeapAtMost512MiB() {
        long d = EgressBudget.defaultGlobalCap();
        assertTrue(d > 0 && d <= 512 * MIB && d <= Runtime.getRuntime().maxMemory() / 4, String.valueOf(d));
    }
}
