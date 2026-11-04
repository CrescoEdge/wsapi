package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review (review/api-20261104): ways around wsapi's failed-login limits, and ways to turn them against
 * real users. Each test states what a production-grade limiter must guarantee; the hashes here use few PBKDF2
 * iterations only so the tests run fast (the limiter never looks at the hash).
 */
class ReviewApiAuthBypassTest {

    static final SecureRandom R = new SecureRandom();
    static final String ALICE_PW = "alice-password-1";
    static final String MALLORY_PW = "mallory-password-2";

    /** alice (victim, admin) and mallory (the attacker's own ordinary account) in tenant-a; cheap hashes. */
    static CredentialStore store() {
        return CredentialStore.empty()
                .with(new CredentialStore.Entry("alice", "tenant-a", "admin", PasswordHash.create(ALICE_PW.toCharArray(), R, 1000)))
                .with(new CredentialStore.Entry("mallory", "tenant-a", "user", PasswordHash.create(MALLORY_PW.toCharArray(), R, 1000)));
    }

    static String basic(String user, String pw) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    static Authenticator auth(AuthRateLimiter limiter, String serviceKey) {
        CredentialStore s = store();
        return new Authenticator(() -> s, () -> serviceKey, () -> true, () -> "tenant-a", Set::of, limiter, 300_000L,
                System::currentTimeMillis);
    }

    /**
     * The service key is an admin of the agent's tenant, but its failures are counted per source address only (never
     * against a "service-key" user bucket the way a password guess counts against its user). An attacker with many
     * addresses (an IPv6 /64, a botnet) guesses it without limit.
     */
    @Test
    void serviceKeyGuessesFromManyAddressesAreEventuallyLimited() {
        Authenticator a = auth(AuthRateLimiter.defaults(), "the-real-service-key-0123456789");
        int checked = 0, limited = 0;
        for (int i = 1; i <= 200; i++) {
            Authenticator.Result r = a.authenticate(null, "guess-" + i, "2001:db8:0:1::" + Integer.toHexString(i));
            if (r.status == 429) limited++;
            else checked++;
        }
        assertTrue(limited > 0, "all " + checked + " service-key guesses (one per address) were checked; a password gets "
                + "at most 50 per minute per user, the admin-equivalent service key has no such bound");
    }

    /**
     * A success from a source clears that source's failures and lockouts, whoever succeeded. An attacker with any
     * valid account (here an ordinary user of the same tenant) logs in between guesses and so is never limited by the
     * per-source rule; only the per-user bound (50/min per victim) is left.
     */
    @Test
    void theAttackersOwnLoginDoesNotResetItsFailuresAgainstAVictim() {
        Authenticator a = auth(AuthRateLimiter.defaults(), null);
        String src = "198.51.100.7";
        int guesses = 0, limited = 0;
        for (int round = 0; round < 9; round++) {
            for (int g = 0; g < 5; g++) {
                Authenticator.Result r = a.authenticate(basic("alice", "guess-" + round + "-" + g), null, src);
                guesses++;
                if (r.status == 429) limited++;
            }
            assertTrue(a.authenticate(basic("mallory", MALLORY_PW), null, src).ok());
        }
        assertTrue(limited > 0, guesses + " wrong passwords for alice from one address (the per-source limit is 10) and "
                + "none was refused: mallory's own logins in between reset the address's count");
    }

    /**
     * The per-user bound locks the user name itself, for everyone. Five addresses with ten wrong passwords each lock
     * alice out, and the real alice, with her right password from her own address, is refused (429) for up to 15
     * minutes, renewable by the attacker at will.
     */
    @Test
    void failuresFromOtherAddressesDoNotLockTheRealUserOut() {
        Authenticator a = auth(AuthRateLimiter.defaults(), null);
        for (int s = 1; s <= 5; s++) {
            for (int g = 0; g < 10; g++) a.authenticate(basic("alice", "wrong-" + g), null, "203.0.113." + s);
        }
        Authenticator.Result real = a.authenticate(basic("alice", ALICE_PW), null, "192.0.2.10");
        assertTrue(real.ok(), "the real alice, right password, her own address: HTTP " + real.status + " (" + real.logReason + ")");
    }

    /**
     * The source table holds 10,000 addresses; when it is full every address not already in it shares ONE overflow
     * bucket. Failures that cost the server nothing (a malformed Authorization header, no PBKDF2) from 10,010 addresses
     * fill the table and lock the bucket, and from then on every new client, with good credentials, gets 429.
     */
    @Test
    void cheapFailuresFromManyAddressesDoNotLockOutEveryNewClient() {
        Authenticator a = auth(AuthRateLimiter.defaults(), null);
        for (int i = 0; i < 10_010; i++) {
            Authenticator.Result r = a.authenticate("Basic !!!", null, "2001:db8:0:2::" + Integer.toHexString(i + 1));
            assertNotEquals(0, r.status);
        }
        Authenticator.Result real = a.authenticate(basic("alice", ALICE_PW), null, "192.0.2.20");
        assertTrue(real.ok(), "a client never seen before, right password: HTTP " + real.status + " (" + real.logReason + ")");
    }
}
