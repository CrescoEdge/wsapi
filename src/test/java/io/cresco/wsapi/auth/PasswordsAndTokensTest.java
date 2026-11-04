package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Round 3: strong passwords (the CLI and at login) and per-user API tokens. */
class PasswordsAndTokensTest {

    static final SecureRandom R = new SecureRandom();

    @Test
    void thePolicyRefusesShortSmallRepeatedWellKnownAndRunPasswords() {
        assertNotNull(PasswordPolicy.weakness("alice", "short-1".toCharArray()));
        assertNotNull(PasswordPolicy.weakness("alice", "aaaaaaaaaaaaaaaa".toCharArray()), "too few distinct characters");
        assertNotNull(PasswordPolicy.weakness("alice", "abcdefghijklmn".toCharArray()), "a run");
        assertNotNull(PasswordPolicy.weakness("alice", "abcdeflmnopq".toCharArray()), "12 lower-case letters: about 56 bits");
        assertNotNull(PasswordPolicy.weakness("alice", "Administrator".toCharArray()), "well known");
        assertNotNull(PasswordPolicy.weakness("alice", "Password2024!".toCharArray()), "a well-known word with a few digits");
        assertNotNull(PasswordPolicy.weakness("administrator", "Administrator".toCharArray()));
        assertNull(PasswordPolicy.weakness("alice", "alice-password-1".toCharArray()));
        assertNull(PasswordPolicy.weakness("alice", "violet anchor tundra pickle".toCharArray()));
        assertNotNull(PasswordPolicy.weakness("alice", "correct horse battery staple".toCharArray()), "the famous example is well known");
        assertNull(PasswordPolicy.weakness("alice", "kq7#Vm2!pR9x".toCharArray()));
        String why = PasswordPolicy.weakness("alice", "abcdeflmnopq".toCharArray());
        assertFalse(why.contains("abcdeflmnopq"), "never echoes the password: " + why);
    }

    static int tool(String[] args, String password, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return CredentialTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                prompt -> password == null ? null : password.toCharArray());
    }

    @Test
    void theToolRefusesAWeakPasswordAndManagesTokens(@TempDir Path dir) throws Exception {
        Path users = dir.resolve("ws.users");
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        assertEquals(1, tool(new String[]{"add", users.toString(), "alice", "tenant-a", "admin"}, "password1234", out, err));
        assertTrue(err.toString().contains("refused"), err.toString());
        assertEquals(0, tool(new String[]{"add", users.toString(), "alice", "tenant-a", "admin"}, "alice-password-1", out, err));

        out.reset();
        assertEquals(0, tool(new String[]{"token", "add", users.toString(), "alice", "--expires-days", "30", "--label", "gaiakeep sync"}, null, out, err));
        String token = out.toString().strip();
        assertTrue(CredentialStore.Token.FORM.matcher(token).matches(), token);
        String file = Files.readString(users);
        assertFalse(file.contains(token.substring(token.indexOf('.') + 1)), "only the token's hash is stored");
        CredentialStore store = CredentialStore.parse(file, "t");
        CredentialStore.Token t = store.tokens().get(0);
        assertEquals("alice", t.user);
        assertEquals("gaiakeep sync", t.label);
        assertTrue(t.expiresSeconds > t.createdSeconds);
        assertTrue(t.matches(token));

        out.reset();
        assertEquals(0, tool(new String[]{"token", "list", users.toString()}, null, out, err));
        assertTrue(out.toString().contains(t.id) && !out.toString().contains(token), out.toString());
        assertEquals(0, tool(new String[]{"token", "revoke", users.toString(), t.id}, null, out, err));
        assertTrue(CredentialStore.parse(Files.readString(users), "t").tokens().isEmpty());

        assertEquals(0, tool(new String[]{"token", "add", users.toString(), "alice"}, null, out, err));
        assertEquals(0, tool(new String[]{"remove", users.toString(), "alice"}, null, out, err));
        assertTrue(CredentialStore.parse(Files.readString(users), "t").tokens().isEmpty(), "a removed user's tokens go too");
    }

    @Test
    void aTokenOfAnUnknownUserIsAnInvalidFile() {
        String line = "+token:0123456789abcdef:ghost:" + "0".repeat(96) + ":1:0:x\n";
        assertThrows(CredentialStore.Invalid.class, () -> CredentialStore.parse(line, "t"));
    }

    @Test
    void aTokenAuthenticatesIsNeverThrottledPerUserAndCanExpireOrBeRevoked() throws Exception {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        String presented = String.join("", "cwt1_", "0011223344556677", ".", "A".repeat(43));   // test-only
        CredentialStore.Token t = new CredentialStore.Token("0011223344556677", "alice", CredentialStore.Token.hash(presented),
                now.get() / 1000, now.get() / 1000 + 3600, "tool");
        CredentialStore[] store = {CredentialStore.empty()
                .with(new CredentialStore.Entry("alice", "tenant-a", "user", PasswordHash.create("alice-password-1".toCharArray(), R, 1000)))
                .withToken(t)};
        AuthRateLimiter limiter = new AuthRateLimiter(10, 50, 60_000L, 60_000L, 900_000L, 100_000, now::get);
        Authenticator a = new Authenticator(() -> store[0], () -> null, () -> true, () -> "tenant-a", Set::of, limiter, 0L, now::get);

        // strangers spend alice's whole password budget from 500 networks
        for (int i = 0; i < 500; i++) {
            a.authenticate(PasswordHashTestSupport.basic("alice", "guess-" + i), null, "10." + (i / 256) + "." + (i % 256) + ".1");
        }
        Authenticator.Result r = a.authenticate("Bearer " + presented, null, "192.0.2.200");
        assertTrue(r.ok(), "alice's tool, from a new host, with its token: HTTP " + r.status + " " + r.logReason);
        assertEquals("alice", r.principal.user);

        Authenticator.Result wrong = a.authenticate("Bearer " + presented.replace('A', 'B'), null, "192.0.2.201");
        assertEquals(401, wrong.status);
        assertEquals(401, a.authenticate("Bearer not-a-token", null, "192.0.2.202").status);
        now.addAndGet(3_600_001L);
        assertEquals(401, a.authenticate("Bearer " + presented, null, "192.0.2.200").status, "expired");
        assertTrue(a.authenticate("Bearer " + presented, null, "192.0.2.200").logReason.contains("expired"));
        store[0] = store[0].withoutToken(t.id);
        assertEquals(401, a.authenticate("Bearer " + presented, null, "192.0.2.200").status, "revoked");
    }

    @Test
    void aWeakPasswordSetBeforeThePolicyStopsWorkingUntilItIsChanged() {
        CredentialStore s = CredentialStore.empty()
                .with(new CredentialStore.Entry("old", "tenant-a", "user", PasswordHash.create("password1234".toCharArray(), R, 1000)));
        Authenticator a = new Authenticator(() -> s, () -> null, () -> true, () -> "tenant-a", Set::of, AuthRateLimiter.defaults(), 0L,
                System::currentTimeMillis);
        Authenticator.Result r = a.authenticate(PasswordHashTestSupport.basic("old", "password1234"), null, "192.0.2.9");
        assertEquals(401, r.status);
        assertTrue(r.logReason.contains("does not meet the password policy") && r.logReason.contains("CredentialTool passwd"), r.logReason);
        assertFalse(r.logReason.contains("password1234"));
    }
}
