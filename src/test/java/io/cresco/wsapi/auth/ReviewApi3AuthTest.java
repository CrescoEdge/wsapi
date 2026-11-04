package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 3 (review/api-20261104): the known-good store and the per-user budget for unknown sources.
 * Driven through the limiter's own API with a simulated clock.
 */
class ReviewApi3AuthTest {

    final AtomicLong now = new AtomicLong(1_000_000_000L);

    AuthRateLimiter limiter() {
        return new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 100_000, now::get);
    }

    /** 50 wrong passwords for {@code user}, one from each of 50 IPv4 addresses (no address is limited by one failure). */
    void distributedGuess(AuthRateLimiter l, String user) {
        for (int i = 1; i <= 50; i++) {
            assertEquals(0, l.retryAfterMs("203.0.113." + i, user));
            l.failure("203.0.113." + i, user);
            now.addAndGet(100);
        }
    }

    /**
     * Every success adds the user's exact address to ONE store of 100,000 entries shared by all users, least recently used
     * first out. An ordinary user owns a whole IPv6 /64 (2^64 exact addresses) and logs in cheaply (a remembered password
     * costs no PBKDF2; successes are never limited), so 100,000 logins from new addresses of their own /64 push every
     * other user's known-good addresses out. alice is then an unknown source at her own desk, and a distributed guess at
     * her password (50 failures a minute from anywhere) refuses her there.
     */
    @Test
    void anOrdinaryUserCannotFlushOtherUsersKnownGoodSources() {
        AuthRateLimiter control = limiter();
        control.success("198.51.100.10", "alice");
        distributedGuess(control, "alice");
        assertEquals(0, control.retryAfterMs("198.51.100.10", "alice"), "control: alice at her known desk is never refused");

        AuthRateLimiter l = limiter();
        l.success("198.51.100.10", "alice");
        now.addAndGet(1_000);
        for (int i = 0; i < 100_000; i++) {
            l.success("2001:db8:b0b:1:0:1:" + Integer.toHexString(i >>> 16) + ":" + Integer.toHexString(i & 0xffff), "bob");
        }
        distributedGuess(l, "alice");
        long wait = l.retryAfterMs("198.51.100.10", "alice");
        assertEquals(0, wait, "alice at her own desk, after bob's 100,000 logins and a distributed guess: refused for " + wait + " ms");
    }

    /**
     * A user with no known-good source yet (a new account, a first login, any login after the 30-day memory or after the
     * store was reset) is refused everywhere while 50 wrong passwords a minute arrive for that user name from anywhere.
     * The same holds for a known user at any new place (a hotel, a phone that changes its /64). The budget bounds guessing,
     * but it is also a lockout that any stranger who knows a user name can hold indefinitely.
     */
    @Test
    void aUserWithNoKnownSourceIsNotLockedOutByStrangers() {
        AuthRateLimiter l = limiter();
        distributedGuess(l, "carol");
        long wait = l.retryAfterMs("192.0.2.77", "carol");
        assertEquals(0, wait, "carol's first login, right password, her own address: refused for " + wait + " ms");
    }
}
