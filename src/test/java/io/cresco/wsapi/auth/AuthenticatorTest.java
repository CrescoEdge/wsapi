package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatorTest {

    static final SecureRandom R = new SecureRandom();
    static final String ALICE_PW = "alice-password-1";
    static final CredentialStore STORE = CredentialStore.parse(
            "alice:tenant-a:admin:" + PasswordHash.create(ALICE_PW.toCharArray(), R).encode() + "\n"
                    + "bob:tenant-a:user:" + PasswordHash.create("bob-password-22".toCharArray(), R).encode() + "\n"
                    + "eve:tenant-b:user:" + PasswordHash.create("eve-password-333".toCharArray(), R).encode() + "\n"
                    + "root:ops:admin:" + PasswordHash.create("root-password-4444".toCharArray(), R).encode() + "\n"
                    + "mallory:tenant-b:admin:" + PasswordHash.create("mallory-password-5".toCharArray(), R).encode() + "\n", "t");

    final AtomicReference<CredentialStore> store = new AtomicReference<>(STORE);
    final AtomicReference<String> serviceKey = new AtomicReference<>("the-service-key-value-0123456789abcdef");
    final AtomicBoolean serviceKeyEnabled = new AtomicBoolean(true);
    final AtomicReference<String> tenant = new AtomicReference<>("tenant-a");
    final AtomicLong now = new AtomicLong(1_000_000L);
    final AuthRateLimiter limiter = new AuthRateLimiter(5, 50, 60_000L, 60_000L, 900_000L, 1000, now::get);
    final Authenticator auth = new Authenticator(store::get, serviceKey::get, serviceKeyEnabled::get, tenant::get,
            () -> Set.of("ops"), limiter, 300_000L, now::get);

    static String basic(String user, String pw) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aUserAuthenticatesWithTenantAndRole() {
        Authenticator.Result r = auth.authenticate(basic("alice", ALICE_PW), null, "10.0.0.1");
        assertTrue(r.ok(), r.logReason);
        assertEquals("tenant-a", r.principal.tenant);
        assertEquals("alice", r.principal.user);
        assertEquals("admin", r.principal.role);
        assertFalse(r.principal.serviceKey);
        assertEquals("api:tenant-a/alice[admin]", r.principal.toString());
    }

    @Test
    void wrongPasswordUnknownUserAndMalformedHeadersGet401WithTheSameMessage() {
        Authenticator.Result wrong = auth.authenticate(basic("alice", "nope"), null, "s1");
        Authenticator.Result unknown = auth.authenticate(basic("zed", "nope"), null, "s2");
        Authenticator.Result garbage = auth.authenticate("Basic !!!", null, "s3");
        Authenticator.Result scheme = auth.authenticate("Bearer abc", null, "s4");
        Authenticator.Result none = auth.authenticate(null, null, "s5");
        for (Authenticator.Result r : new Authenticator.Result[]{wrong, unknown, garbage, scheme, none}) {
            assertEquals(401, r.status);
            assertNull(r.principal);
        }
        assertEquals(wrong.clientMessage, unknown.clientMessage, "the client cannot tell a wrong password from an unknown user");
        assertFalse(wrong.logReason.contains("nope") || wrong.clientMessage.contains("nope"), "never echo a secret");
    }

    @Test
    void theServiceKeyIsAnAdminOfTheAgentsTenantAndCanBeDisabled() {
        Authenticator.Result r = auth.authenticate(null, "the-service-key-value-0123456789abcdef", "s");
        assertTrue(r.ok());
        assertEquals("tenant-a", r.principal.tenant);
        assertEquals("admin", r.principal.role);
        assertEquals("service-key", r.principal.user);
        assertTrue(r.principal.serviceKey);
        assertEquals(401, auth.authenticate(null, "the-service-key-value-0123456789abcdeE", "s").status);
        serviceKeyEnabled.set(false);
        assertEquals(401, auth.authenticate(null, "the-service-key-value-0123456789abcdef", "s2").status);
        serviceKeyEnabled.set(true);
        serviceKey.set(null);
        assertEquals(401, auth.authenticate(null, "the-service-key-value-0123456789abcdef", "s3").status, "no key configured: nothing matches");
    }

    @Test
    void basicWinsOverTheServiceKeyWhenBothAreSent() {
        Authenticator.Result r = auth.authenticate(basic("bob", "bob-password-22"), "the-service-key-value-0123456789abcdef", "s");
        assertTrue(r.ok());
        assertEquals("bob", r.principal.user);
        assertEquals(401, auth.authenticate(basic("bob", "x"), "the-service-key-value-0123456789abcdef", "s").status, "a valid key does not rescue a bad password");
    }

    @Test
    void aCallerOfAnotherTenantIsRefusedUnlessASuperuserTenantAdmin() {
        Authenticator.Result eve = auth.authenticate(basic("eve", "eve-password-333"), null, "s");
        assertEquals(403, eve.status);
        assertTrue(eve.clientMessage.contains("tenant-b") && eve.clientMessage.contains("tenant-a"));
        assertEquals(403, auth.authenticate(basic("mallory", "mallory-password-5"), null, "s").status, "admin of an ordinary other tenant");
        Authenticator.Result root = auth.authenticate(basic("root", "root-password-4444"), null, "s");
        assertTrue(root.ok(), "an admin of a superuser tenant may use any tenant's wsapi");
        assertTrue(auth.mayTarget(root.principal, "tenant-b"));
        Authenticator.Result bob = auth.authenticate(basic("bob", "bob-password-22"), null, "s");
        assertTrue(auth.mayTarget(bob.principal, "tenant-a"));
        assertFalse(auth.mayTarget(bob.principal, "tenant-b"), "a user targets only its own tenant");
        assertFalse(auth.mayTarget(auth.authenticate(basic("alice", ALICE_PW), null, "s").principal, "tenant-b"),
                "an admin of an ordinary tenant targets only its own tenant");
    }

    @Test
    void anUnknownAgentTenantFailsClosed() {
        tenant.set(null);
        assertEquals(503, auth.authenticate(basic("alice", ALICE_PW), null, "s").status);
        assertEquals(503, auth.authenticate(null, "the-service-key-value-0123456789abcdef", "s").status);
    }

    @Test
    void repeatedFailuresAreRateLimitedEvenWithTheRightPassword() {
        int refused = 0;
        for (int i = 0; i < 5; i++) {
            int st = auth.authenticate(basic("alice", "guess" + i), null, "10.6.6.6").status;
            assertTrue(st == 401 || st == 429, "status " + st);
            if (st == 429) refused++;
        }
        assertTrue(refused > 0, "alice's delays at this source start after " + AuthRateLimiter.FREE_PAIR_FAILURES + " failures");
        Authenticator.Result r = auth.authenticate(basic("alice", ALICE_PW), null, "10.6.6.6");
        assertEquals(429, r.status, "the right password from the guessing source waits too");
        assertTrue(r.retryAfterSeconds >= 1);
        assertTrue(auth.authenticate(basic("alice", ALICE_PW), null, "10.0.0.9").ok(), "alice elsewhere is unaffected");
        for (int i = 0; i < 5; i++) auth.authenticate(null, "wrong-key", "10.7.7.7");
        assertEquals(429, auth.authenticate(null, "the-service-key-value-0123456789abcdef", "10.7.7.7").status, "service key guesses count too");
        now.addAndGet(16 * 60_000L);
        assertTrue(auth.authenticate(basic("alice", ALICE_PW), null, "10.6.6.6").ok(), "the delays run out");
    }

    @Test
    void aWeakServiceKeyIsNeverAccepted() {
        for (String weak : new String[]{"12345", "cresco", "changeit", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "short-key-1", "abcdefghijklmnopqrst", "0123456789012345678901234567890123"}) {
            assertNotNull(Authenticator.serviceKeyWeakness(weak), weak);
            serviceKey.set(weak);
            Authenticator.Result r = auth.authenticate(null, weak, "weak-" + weak.length());
            assertEquals(401, r.status, weak);
            assertTrue(r.logReason.contains("too weak"), r.logReason);
            // ("cresco" is part of the setting's name, cresco_service_key, in the reason)
            assertFalse(!weak.equals("cresco") && r.logReason.contains(weak), "the key itself is never logged");
        }
        for (String strong : new String[]{"0f1e2d3c4b5a69788796a5b4c3d2e1f0",                 // 32 hex digits, 128 bits
                "Zk3pQ9vXr2LmT8wYb5NcJ7hG", "the-service-key-value-0123456789abcdef", "9f8b2c4e-1a3d-4f6b-8c7e-2d5a9b1c3e7f"}) {
            assertNull(Authenticator.serviceKeyWeakness(strong), strong);
        }
        serviceKey.set("0f1e2d3c4b5a69788796a5b4c3d2e1f0");
        assertTrue(auth.authenticate(null, "0f1e2d3c4b5a69788796a5b4c3d2e1f0", "strong").ok());
        assertNull(auth.serviceKeyProblem());
    }

    @Test
    void theFastPathDecidesEverythingThatNeedsNoPbkdf2() {
        assertEquals(401, auth.authenticateFast("Basic !!!", null, "f1").status, "malformed");
        assertEquals(401, auth.authenticateFast(null, null, "f2").status, "nothing");
        assertTrue(auth.authenticateFast(null, "the-service-key-value-0123456789abcdef", "f3").ok(), "the service key");
        assertNull(auth.authenticateFast(basic("alice", ALICE_PW), null, "f4"), "an unremembered password needs PBKDF2");
        assertTrue(auth.authenticate(basic("alice", ALICE_PW), null, "f4").ok());
        Authenticator.Result again = auth.authenticateFast(basic("alice", ALICE_PW), null, "f5");
        assertNotNull(again, "a remembered password is decided without PBKDF2");
        assertTrue(again.ok());
        assertNull(auth.authenticateFast(basic("alice", "not-it"), null, "f6"), "a wrong password still needs PBKDF2");
    }

    @Test
    void aVerifiedPasswordIsRememberedUntilItExpiresOrTheStoreChanges() {
        assertTrue(auth.authenticate(basic("alice", ALICE_PW), null, "s").ok());
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) assertTrue(auth.authenticate(basic("alice", ALICE_PW), null, "s").ok());
        long perCallMs = (System.nanoTime() - t0) / 20 / 1_000_000L;
        assertTrue(perCallMs < 50, "remembered passwords skip PBKDF2 (took " + perCallMs + " ms per call)");
        assertEquals(401, auth.authenticate(basic("alice", "wrong"), null, "s").status, "only the remembered password");
        // a store change (password changed) forgets it
        store.set(CredentialStore.parse("alice:tenant-a:admin:" + PasswordHash.create("new-password-99".toCharArray(), R).encode(), "t2"));
        assertEquals(401, auth.authenticate(basic("alice", ALICE_PW), null, "s").status);
        assertTrue(auth.authenticate(basic("alice", "new-password-99"), null, "s").ok());
    }

    @Test
    void basicHeaderParsing() {
        assertArrayEquals(new String[]{"alice", "pw:with:colons"}, Authenticator.parseBasic(basic("alice", "pw:with:colons")));
        assertArrayEquals(new String[]{"alice", "pw"}, Authenticator.parseBasic("bAsIc " + Base64.getEncoder().encodeToString("alice:pw".getBytes())));
        assertNull(Authenticator.parseBasic(basic("", "pw")));
        assertNull(Authenticator.parseBasic(basic("alice", "")));
        assertNull(Authenticator.parseBasic(basic("al ice", "pw")));
        assertNull(Authenticator.parseBasic("Basic"));
    }
}
