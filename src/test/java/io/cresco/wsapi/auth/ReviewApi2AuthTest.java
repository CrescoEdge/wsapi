package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 2 (review/api-20261104): the redesigned limiter (IPv6 /64 sources, per-pair delays, the
 * fabric-wide service-key bucket). Cheap PBKDF2 iterations only so the tests run fast (the limiter never looks at the
 * hash); the clock is simulated.
 */
class ReviewApi2AuthTest {

    static final SecureRandom R = new SecureRandom();
    static final String ALICE_PW = "alice-password-1";
    static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef";   // 48 hex digits: a strong key

    final AtomicLong now = new AtomicLong(1_000_000_000L);

    static CredentialStore store() {
        return CredentialStore.empty()
                .with(new CredentialStore.Entry("alice", "tenant-a", "admin", PasswordHash.create(ALICE_PW.toCharArray(), R, 1000)));
    }

    Authenticator auth(AuthRateLimiter limiter) {
        CredentialStore s = store();
        return new Authenticator(() -> s, () -> KEY, () -> true, () -> "tenant-a", Set::of, limiter, 300_000L, now::get);
    }

    AuthRateLimiter defaults() {
        return new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 100_000, now::get);
    }

    static String basic(String user, String pw) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    /** The source wsapi counts an address against (WsRouter.sourceOf: an IPv4 address or an IPv6 /64). */
    static String source(String ip) throws Exception {
        Method m = Class.forName("io.cresco.wsapi.netty.WsRouter").getDeclaredMethod("sourceOf", SocketAddress.class);
        m.setAccessible(true);
        return (String) m.invoke(null, new InetSocketAddress(InetAddress.getByName(ip), 40000));
    }

    /**
     * The fabric-wide service-key bucket refuses every source that has not used the key successfully in the last day,
     * as long as 30 wrong keys arrive per minute from anywhere (they cost the server nothing). The trust list lives in
     * memory, so after an agent restart, or from a new host (a new GaiaKeep tool, a new container), the real key is
     * answered 429 for as long as an unauthenticated stranger keeps sending wrong keys.
     */
    @Test
    void wrongKeysFromStrangersDoNotLockTheRealKeyOutOfANewHost() throws Exception {
        Authenticator a = auth(defaults());                       // a freshly started agent
        for (int s = 1; s <= 30; s++) {                           // one wrong key from each of 30 addresses
            a.authenticate(null, "wrong-key-" + s, source("198.51.100." + s));
            now.addAndGet(100);
        }
        Authenticator.Result real = a.authenticate(null, KEY, source("192.0.2.50"));
        assertTrue(real.ok(), "the real service key from a host it was never used from: HTTP " + real.status + " (" + real.logReason + ")");
    }

    /**
     * Since sources are /64s, everyone on one LAN (SLAAC hands every device its own address in the same /64) is one
     * source, and a (user, source) pair's growing delay applies to the real user too, known good or not. A stranger on
     * alice's network (guest Wi-Fi, a compromised device) sends wrong passwords for alice and keeps her own logins
     * refused, up to 15 minutes at a time.
     */
    @Test
    void aStrangerOnTheSameSlash64CannotDelayTheRealUser() throws Exception {
        Authenticator a = auth(defaults());
        String alicesLaptop = source("2001:db8:1:2::10");
        assertTrue(a.authenticate(basic("alice", ALICE_PW), null, alicesLaptop).ok(), "alice is known good on her network");
        now.addAndGet(5_000);
        String strangersPhone = source("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        for (int g = 0; g < 10; g++) {                            // a patient stranger: waits out each delay
            Authenticator.Result r = a.authenticate(basic("alice", "wrong-" + g), null, strangersPhone);
            now.addAndGet(r.status == 429 ? r.retryAfterSeconds * 1000L : 200);
            if (r.status == 429) a.authenticate(basic("alice", "wrong-" + g), null, strangersPhone);
        }
        Authenticator.Result real = a.authenticate(basic("alice", ALICE_PW), null, alicesLaptop);
        assertTrue(real.ok(), "alice, right password, her own laptop (a different address in the same /64): HTTP "
                + real.status + " (" + real.logReason + ")");
    }

    /**
     * The per-user bound is gone: a user name's failures from all sources only reduce each NEW source's free attempts to
     * one. A guesser with many addresses (an IPv6 /48 has 65,536 /64s) has every one of them checked. Within one minute,
     * 500 addresses get 500 password checks against alice; the earlier design checked at most 50.
     */
    @Test
    void distributedGuessesAgainstOneUserStayBounded() throws Exception {
        Authenticator a = auth(defaults());
        int checked = 0;
        for (int i = 1; i <= 500; i++) {
            Authenticator.Result r = a.authenticate(basic("alice", "guess-" + i),
                    null, source("2001:db8:" + Integer.toHexString(0x100 + i) + ":1::1"));
            if (r.status != 429) checked++;
            now.addAndGet(50);
        }
        assertTrue(checked <= 100, checked + " of 500 guesses at alice's password in 25 s were checked");
    }
}
