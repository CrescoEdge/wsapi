package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private AuthRateLimiter limiter(int perSource, int perUser, int tracked) {
        return new AuthRateLimiter(perSource, perUser, 60_000L, 60_000L, 900_000L, tracked, now::get);
    }

    @Test
    void aSourceIsLockedAfterTooManyFailuresAndTheLockDoubles() {
        AuthRateLimiter l = limiter(3, 100, 100);
        l.failure("10.0.0.1", "alice");
        l.failure("10.0.0.1", "alice");
        assertEquals(0, l.retryAfterMs("10.0.0.1", "alice"));
        l.failure("10.0.0.1", "alice");
        assertEquals(60_000L, l.retryAfterMs("10.0.0.1", "alice"));
        assertEquals(0, l.retryAfterMs("10.0.0.2", "alice"), "another source is not locked");
        now.addAndGet(60_001L);
        assertEquals(0, l.retryAfterMs("10.0.0.1", null));
        for (int i = 0; i < 3; i++) l.failure("10.0.0.1", null);
        assertEquals(120_000L, l.retryAfterMs("10.0.0.1", null), "a repeat lockout doubles");
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        AuthRateLimiter l = limiter(3, 100, 100);
        l.failure("s", null);
        l.failure("s", null);
        now.addAndGet(61_000L);
        l.failure("s", null);
        assertEquals(0, l.retryAfterMs("s", null));
    }

    @Test
    void aSuccessForgivesTheSource() {
        AuthRateLimiter l = limiter(3, 100, 100);
        l.failure("s", null);
        l.failure("s", null);
        l.success("s");
        l.failure("s", null);
        l.failure("s", null);
        assertEquals(0, l.retryAfterMs("s", null));
    }

    @Test
    void aUserGuessedFromManySourcesIsLocked() {
        AuthRateLimiter l = limiter(100, 5, 1000);
        for (int i = 0; i < 5; i++) l.failure("10.0.0." + i, "alice");
        assertTrue(l.retryAfterMs("10.9.9.9", "alice") > 0);
        assertEquals(0, l.retryAfterMs("10.9.9.9", "bob"));
    }

    @Test
    void memoryIsBoundedAndTheOverflowFailsClosed() {
        AuthRateLimiter l = limiter(3, 1000, 16);
        for (int i = 0; i < 100; i++) l.failure("src-" + i, null);
        assertTrue(l.trackedSources() <= 17);
        assertTrue(l.retryAfterMs("never-seen", null) > 0, "new sources share the locked overflow bucket");
    }
}
