package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private AuthRateLimiter limiter(int perSource, int perUser, int tracked) {
        return new AuthRateLimiter(perSource, perUser, 60_000L, 60_000L, 900_000L, tracked, now::get);
    }

    @Test
    void aBucketIsLockedAfterTooManyFailuresAndTheLockDoubles() {
        AuthRateLimiter l = limiter(3, 100, 100);
        l.failure("10.0.0.1", "u1");
        l.failure("10.0.0.1", "u2");
        assertEquals(0, l.retryAfterMs("10.0.0.1", "u3"));
        l.failure("10.0.0.1", null);                              // a cheap failure counts against the bucket too
        assertEquals(60_000L, l.retryAfterMs("10.0.0.1", "u3"));
        assertEquals(60_000L, l.retryAfterMs("10.0.0.1", null));
        assertEquals(0, l.retryAfterMs("10.0.0.2", "u3"), "another source is not locked");
        now.addAndGet(60_001L);
        assertEquals(0, l.retryAfterMs("10.0.0.1", null));
        for (int i = 0; i < 3; i++) l.failure("10.0.0.1", "u" + (10 + i));
        assertEquals(120_000L, l.retryAfterMs("10.0.0.1", null), "a repeat lockout doubles");
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
        assertTrue(l.retryAfterMs("s", "newcomer") > 0, "the victim's failures still count against the source");
        l.success("s", "victim");                                 // the real victim
        assertEquals(0, l.retryAfterMs("s", "newcomer"), "the victim's own success forgives its share");
    }

    @Test
    void anExactKnownGoodAddressIsNeverDelayedByOthersFailures() {
        AuthRateLimiter l = limiter(3, 100, 100);
        String laptop = "2001:db8:1:2::/64 2001:db8:1:2:0:0:0:10", phone = "2001:db8:1:2::/64 2001:db8:1:2:aa:bb:cc:dd";
        l.success(laptop, "alice");
        for (int i = 0; i < 12; i++) l.failure(phone, "alice");  // a stranger on alice's LAN, guessing her password
        assertTrue(l.retryAfterMs(phone, "alice") > 0, "the stranger is limited");
        assertTrue(l.retryAfterMs(phone, "bob") > 0, "the bucket is locked for others");
        assertEquals(0, l.retryAfterMs(laptop, "alice"), "alice's own laptop is not");
        l.failure(laptop, "alice");
        assertEquals(0, l.retryAfterMs(laptop, "alice"), "her own free failures cost nothing");
        for (int i = 0; i < 3; i++) l.failure(laptop, "alice");
        assertTrue(l.retryAfterMs(laptop, "alice") > 0, "her own repeated failures do delay her (that address only)");
    }

    @Test
    void newAddressesInAKnownBucketShareASmallBudget() {
        AuthRateLimiter l = limiter(5, 100, 1000);
        l.success("2001:db8:1:2::/64 2001:db8:1:2:0:0:0:10", "alice");
        int checked = 0;
        for (int i = 0; i < 40; i++) {                            // one guess per rotated address in her /64
            String a = "2001:db8:1:2::/64 2001:db8:1:2:0:0:1:" + Integer.toHexString(i);
            if (l.retryAfterMs(a, "alice") == 0) {
                checked++;
                l.failure(a, "alice");
            }
        }
        assertTrue(checked <= 5, checked + " rotated addresses were checked");
    }

    @Test
    void aUsersUnknownSourcesShareABoundedBudgetKnownHostsStillWork() {
        AuthRateLimiter l = limiter(1000, 50, 10_000);
        l.success("192.0.2.1", "alice");                          // her office
        int checked = 0;
        for (int i = 0; i < 500; i++) {
            String a = "2001:db8:" + Integer.toHexString(0x100 + i) + ":1::/64 2001:db8:" + Integer.toHexString(0x100 + i) + ":1:0:0:0:1";
            if (l.retryAfterMs(a, "alice") == 0) {
                checked++;
                l.failure(a, "alice");
            }
            now.addAndGet(50);
        }
        assertEquals(50, checked, "at most 50 password checks per minute against one user from unknown sources");
        assertEquals(0, l.retryAfterMs("192.0.2.1", "alice"), "alice from her known office is not refused");
        assertEquals(0, l.retryAfterMs("203.0.113.9", "bob"), "other users are not affected");
        now.addAndGet(60_000L);
        assertEquals(0, l.retryAfterMs("203.0.113.9", "alice"), "the budget renews every window");
    }

    @Test
    void wrongServiceKeysNeverGateTheKeyButRaiseAnAlarm() {
        AuthRateLimiter l = limiter(10, 50, 1000);
        long before = AuthRateLimiter.SERVICE_KEY_FAILURES.get();
        for (int i = 0; i < 100; i++) l.failure("198.51.100." + i, AuthRateLimiter.SERVICE_KEY);
        assertEquals(100, AuthRateLimiter.SERVICE_KEY_FAILURES.get() - before, "every wrong key is counted");
        assertEquals(0, l.retryAfterMs("192.0.2.50", AuthRateLimiter.SERVICE_KEY), "the key from a host it was never used from");
        String alarm = l.serviceKeyAlarm();
        assertNotNull(alarm);
        assertTrue(alarm.contains("100 wrong service keys") && alarm.contains("100 source(s)"), alarm);
        assertNull(l.serviceKeyAlarm(), "once per window");
        for (int i = 0; i < 9; i++) l.failure("203.0.113.5", AuthRateLimiter.SERVICE_KEY);
        l.failure("203.0.113.5", AuthRateLimiter.SERVICE_KEY);
        assertTrue(l.retryAfterMs("203.0.113.5", AuthRateLimiter.SERVICE_KEY) > 0, "a source is still limited");
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
    void knownGoodSourcesSurviveARestartAsKeyedHashes(@TempDir Path dir) throws Exception {
        List<String> warnings = new ArrayList<>();
        AuthRateLimiter before = limiter(3, 50, 1000);
        KnownGoodFile f = new KnownGoodFile(dir, warnings::add);
        f.load(before);
        before.success("2001:db8:1:2::/64 2001:db8:1:2:0:0:0:10", "alice");
        f.saveIfChanged(before);
        String stored = Files.readString(dir.resolve(KnownGoodFile.ENTRIES_FILE));
        assertFalse(stored.contains("alice") || stored.contains("2001:db8"), "no user or address in clear: " + stored);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(KnownGoodFile.ENTRIES_FILE))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(KnownGoodFile.KEY_FILE))));

        AuthRateLimiter after = limiter(3, 50, 1000);                  // a restart
        new KnownGoodFile(dir, warnings::add).load(after);
        for (int i = 0; i < 12; i++) after.failure("2001:db8:1:2::/64 2001:db8:1:2:aa:bb:cc:dd", "alice");
        assertEquals(0, after.retryAfterMs("2001:db8:1:2::/64 2001:db8:1:2:0:0:0:10", "alice"), "alice is still known on her laptop");
        assertTrue(warnings.isEmpty(), warnings.toString());

        Files.setPosixFilePermissions(dir.resolve(KnownGoodFile.KEY_FILE), PosixFilePermissions.fromString("rw-r--r--"));
        AuthRateLimiter exposed = limiter(3, 50, 1000);
        new KnownGoodFile(dir, warnings::add).load(exposed);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not owner-only")), warnings.toString());
        for (int i = 0; i < 12; i++) exposed.failure("2001:db8:1:2::/64 2001:db8:1:2:aa:bb:cc:dd", "alice");
        assertTrue(exposed.retryAfterMs("2001:db8:1:2::/64 2001:db8:1:2:0:0:0:10", "alice") > 0, "an exposed store is not trusted");
    }
}
