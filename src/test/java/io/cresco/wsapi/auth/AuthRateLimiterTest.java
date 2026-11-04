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
        l.failure("10.0.0.1", "u1");
        l.failure("10.0.0.1", "u2");
        assertEquals(0, l.retryAfterMs("10.0.0.1", "u3"));
        l.failure("10.0.0.1", null);                              // a cheap failure counts against the source too
        assertEquals(60_000L, l.retryAfterMs("10.0.0.1", "u3"));
        assertEquals(60_000L, l.retryAfterMs("10.0.0.1", null));
        assertEquals(0, l.retryAfterMs("10.0.0.2", "u3"), "another source is not locked");
        now.addAndGet(60_001L);
        assertEquals(0, l.retryAfterMs("10.0.0.1", null));
        for (int i = 0; i < 3; i++) l.failure("10.0.0.1", "u" + (10 + i));
        assertEquals(120_000L, l.retryAfterMs("10.0.0.1", null), "a repeat lockout doubles");
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        AuthRateLimiter l = limiter(3, 100, 100);
        l.failure("s", "a");
        l.failure("s", "b");
        now.addAndGet(61_000L);
        l.failure("s", "c");
        assertEquals(0, l.retryAfterMs("s", "d"));
    }

    @Test
    void aSuccessForgivesOnlyThatUsersOwnFailuresAtThatSource() {
        AuthRateLimiter l = limiter(4, 100, 100);
        l.failure("s", "victim");
        l.failure("s", "victim");
        l.failure("s", "attacker");
        l.success("s", "attacker");                               // the attacker's own login
        l.failure("s", "victim");
        l.failure("s", "victim");
        assertTrue(l.retryAfterMs("s", "victim") > 0, "the victim's failures still count against the source");
        l.success("s", "victim");                                 // the real victim (say, after the lock)
        assertEquals(0, l.retryAfterMs("s", "newcomer"), "the victim's own success forgives its share");
    }

    @Test
    void aKnownUserIsNotLockedOutByOthersFailuresAtItsAddress() {
        AuthRateLimiter l = limiter(3, 100, 100);
        l.success("nat", "alice");
        for (int i = 0; i < 5; i++) l.failure("nat", "x" + i);   // someone else behind the same address
        assertTrue(l.retryAfterMs("nat", "bob") > 0, "the address is locked for others");
        assertEquals(0, l.retryAfterMs("nat", "alice"), "alice has logged in from here and not failed since");
        l.failure("nat", "alice");
        assertTrue(l.retryAfterMs("nat", "alice") > 0, "once alice fails here, the lock applies to her too");
    }

    @Test
    void aUserNameGetsGrowingDelaysPerSourceNeverALockElsewhere() {
        AuthRateLimiter l = limiter(1000, 1000, 100);
        for (int i = 0; i < AuthRateLimiter.FREE_PAIR_FAILURES; i++) {
            assertEquals(0, l.retryAfterMs("s1", "alice"));
            l.failure("s1", "alice");
        }
        assertEquals(0, l.retryAfterMs("s1", "alice"), "the free failures cost no delay");
        l.failure("s1", "alice");
        assertEquals(1000L, l.retryAfterMs("s1", "alice"));
        now.addAndGet(1000L);
        l.failure("s1", "alice");
        assertEquals(2000L, l.retryAfterMs("s1", "alice"), "each further failure doubles the delay");
        assertEquals(0, l.retryAfterMs("s2", "alice"), "alice at another address is not delayed");
        l.success("s1", "alice");
        assertEquals(0, l.retryAfterMs("s1", "alice"));
    }

    @Test
    void underADistributedAttackNewSourcesGetOneFreeFailure() {
        AuthRateLimiter l = limiter(1000, 5, 1000);
        for (int i = 0; i < 5; i++) l.failure("10.0.0." + i, "alice");
        assertEquals(0, l.retryAfterMs("10.9.9.9", "alice"), "a new source may always try");
        l.failure("10.9.9.9", "alice");
        assertEquals(0, l.retryAfterMs("10.9.9.9", "alice"), "one failure is free");
        l.failure("10.9.9.9", "alice");
        assertTrue(l.retryAfterMs("10.9.9.9", "alice") > 0, "the second waits (normally the fourth)");
        assertEquals(0, l.retryAfterMs("10.9.9.10", "alice"), "the real alice from a fresh address is never refused");
        assertEquals(0, l.retryAfterMs("10.9.9.9", "bob"), "other users are not affected");
    }

    @Test
    void memoryIsBoundedAndAFullTableNeverRefusesANewcomer() {
        AuthRateLimiter l = limiter(3, 1000, 16);
        for (int i = 0; i < 100; i++) {
            for (int k = 0; k < 3; k++) l.failure("src-" + i, null);
        }
        assertTrue(l.trackedSources() <= 16);
        assertEquals(0, l.retryAfterMs("never-seen", null), "a full table forgets the oldest, it does not lock newcomers");
        assertTrue(l.retryAfterMs("src-99", null) > 0, "recent sources are still limited");
    }

    @Test
    void theServiceKeyHasAFabricWideFailureCountThatSparesKnownSources() {
        AuthRateLimiter l = limiter(10, 1000, 1000);
        l.success("gaiakeep-host", AuthRateLimiter.SERVICE_KEY);
        for (int i = 0; i < 30; i++) l.failure("10.1." + i + ".1", AuthRateLimiter.SERVICE_KEY);   // one guess per address
        assertTrue(l.retryAfterMs("10.2.0.1", AuthRateLimiter.SERVICE_KEY) > 0, "new addresses are refused key attempts");
        assertEquals(0, l.retryAfterMs("gaiakeep-host", AuthRateLimiter.SERVICE_KEY), "a source that used the key keeps working");
        assertEquals(0, l.retryAfterMs("10.2.0.1", "alice"), "only the service key is throttled");
    }
}
