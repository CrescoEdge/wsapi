package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 4 (review/api-20261104): the fair-share delays for unknown sources, and the password policy.
 */
class ReviewApi4AuthTest {

    final AtomicLong now = new AtomicLong(1_000_000_000L);

    /**
     * Replaces {@code aStrangerInTheVictimsShareSourceCannotKeepHerFromEverBeingChecked} (coordinator decision: a documented
     * limit, not a redesign). The review showed that a stranger in the victim's own bucket (her IPv4 address behind a NAT,
     * or the IPv6 /64 her LAN shares) can keep her password logins from her new addresses waiting indefinitely: by address
     * the two are indistinguishable. The README now says exactly that, and this test asserts the documented behaviour:
     * <ul>
     *   <li>a stranger sharing her network keeps delaying her password login from a new address (today's IPv6 privacy
     *       address) for as long as it keeps failing (the limit);</li>
     *   <li>her exact known-good address on the same network is never delayed;</li>
     *   <li>the same stranger on another network only slows her new address there (she is checked within minutes);</li>
     *   <li>her API token is never throttled per user, so it is accepted from the delayed address throughout.</li>
     * </ul>
     */
    @Test
    void aStrangerSharingTheVictimsNetworkCanDelayHerNewAddressesButNotHerKnownOnesNorHerTokens() {
        AuthRateLimiter l = new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 100_000, now::get);
        String yesterday = "2001:db8:a:1:1111:2222:3333:4444";              // known good
        String today = "2001:db8:a:1:5555:6666:7777:8888";                  // not known, same /64
        l.success(yesterday, "alice");
        java.security.SecureRandom r = new java.security.SecureRandom();
        byte[] secret = new byte[32];
        r.nextBytes(secret);
        String token = "cwt1_00aa11bb22cc33dd." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        CredentialStore store = CredentialStore.empty()
                .with(new CredentialStore.Entry("alice", "tenant-a", "user", PasswordHash.create("alice-Password-k3v9".toCharArray(), r, 1000)))
                .withToken(new CredentialStore.Token("00aa11bb22cc33dd", "alice", CredentialStore.Token.hash(token), now.get() / 1000, 0, "tool"));
        Authenticator auth = new Authenticator(() -> store, () -> null, () -> false, () -> "tenant-a", java.util.Set::of, l, 0L, now::get);

        long start = now.get();
        int aliceChecked = 0, knownDelayed = 0, k = 0;
        for (int sec = 1; sec <= 7200; sec++) {
            now.set(start + sec * 1000L);
            k = burst(l, "2001:db8:a:1:dead:beef:", k);                      // the stranger on her /64
            if (sec % 60 == 30) {
                if (l.retryAfterMs(today, "alice") == 0) aliceChecked++;
                if (l.retryAfterMs(yesterday, "alice") != 0) knownDelayed++;
            }
        }
        assertTrue(k > 10, "control: the stranger was checked");
        assertEquals(0, aliceChecked, "the documented limit: a stranger sharing her /64 delays her new address's password logins");
        assertEquals(0, knownDelayed, "her exact known-good address is never delayed");

        // her API token, from the delayed address, while the stranger keeps failing: never throttled per user
        int tokenRefused = 0;
        for (int i = 0; i < 10; i++) {
            now.addAndGet(1000L);
            k = burst(l, "2001:db8:a:1:dead:beef:", k);
            Authenticator.Result t = auth.authenticateFast("Bearer " + token, null, today);
            if (t == null || !t.ok()) tokenRefused++;
        }
        assertEquals(0, tokenRefused, "her API token is accepted from the delayed address");

        // the same stranger on another network only slows her new address on hers
        AuthRateLimiter other = new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 100_000, now::get);
        other.success(yesterday, "alice");
        start = now.get();
        long firstCheckedAfter = -1;
        k = 0;
        for (int sec = 1; sec <= 600 && firstCheckedAfter < 0; sec++) {
            now.set(start + sec * 1000L);
            k = burst(other, "2001:db8:b:2:dead:beef:", k);
            if (sec % 60 == 30 && other.retryAfterMs(today, "alice") == 0) firstCheckedAfter = sec;
        }
        assertEquals(30, firstCheckedAfter, "from another network the stranger cannot delay her first try at all");
    }

    /** A stranger's burst of failures for alice, each from a new address of {@code prefix}, while it may be checked. */
    private static int burst(AuthRateLimiter l, String prefix, int k) {
        while (k < 10_000_000) {
            k++;
            String stranger = prefix + Integer.toHexString(k >>> 16) + ":" + Integer.toHexString(k & 0xffff);
            if (l.retryAfterMs(stranger, "alice") != 0) break;
            l.failure(stranger, "alice");
        }
        return k;
    }

    /**
     * The strength estimate gives any non-ASCII character a 100-symbol pool. "abcdefabcdef" is refused (about 56 bits),
     * but the same pattern in Cyrillic, "абвгдеабвгде", is accepted (about 80 bits), and so is "раsswоrd1234", the most
     * common password pattern with three Cyrillic look-alike letters (the well-known check compares ASCII letters only).
     */
    @Test
    void nonAsciiLettersDoNotMakeAWeakPasswordStrong() {
        assertNotNull(PasswordPolicy.weakness("bob", "abcdefabcdef".toCharArray()), "control: the ASCII pattern is refused");
        assertNotNull(PasswordPolicy.weakness("bob", "абвгдеабвгде".toCharArray()),
                "the same pattern in Cyrillic letters is accepted");
        assertNotNull(PasswordPolicy.weakness("bob", "раsswоrd1234".toCharArray()),
                "password1234 with Cyrillic look-alike letters is accepted");
    }
}
