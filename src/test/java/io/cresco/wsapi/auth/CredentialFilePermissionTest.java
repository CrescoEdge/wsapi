package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A permission change alone is noticed at the next check (at most a second): a credential file or directory that stops
 * being owner-only is refused, which revokes the open per-user connections; restoring the mode restores the file.
 */
class CredentialFilePermissionTest {

    @Test
    void aChmodAloneIsNoticedRefusesTheFileAndRevokesItsConnectionsAndRestoringItRestoresThem() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-cred-perm");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path file = dir.resolve("ws.users");
        CredentialTool.save(file, CredentialStore.empty().with(new CredentialStore.Entry("bob", "tenant-a", "user",
                PasswordHash.create("bob-Password-q7w9x2".toCharArray(), new SecureRandom()))));
        AtomicLong now = new AtomicLong(1_000_000L);
        CredentialFile cf = new CredentialFile(file, null, now::get);
        Authenticator auth = new Authenticator(cf::current, () -> null, () -> false, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, now::get);
        String basic = "Basic " + Base64.getEncoder().encodeToString("bob:bob-Password-q7w9x2".getBytes(StandardCharsets.UTF_8));
        Authenticator.Result r = auth.authenticate(basic, null, "10.0.0.5");
        assertTrue(r.ok(), r.logReason);
        assertNull(auth.revocation(r.principal), "control: valid");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));       // group-readable
        now.addAndGet(1_001);
        assertEquals(0, cf.current().size(), "a group-readable file is refused at the next check");
        assertEquals("user bob was removed", auth.revocation(r.principal), "and bob's open connections end (fail closed)");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        now.addAndGet(1_001);
        assertEquals(1, cf.current().size(), "owner-only again: loaded again");
        assertNull(auth.revocation(r.principal), "the same credential is valid again (a new login is needed only if it was closed)");

        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwx---"));        // directory group-writable
        now.addAndGet(1_001);
        assertEquals(0, cf.current().size(), "a file in a group-writable directory is refused at the next check");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        now.addAndGet(1_001);
        assertEquals(1, cf.current().size());

        now.addAndGet(500);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(1, cf.current().size(), "checked at most once a second");
        now.addAndGet(600);
        assertEquals(0, cf.current().size());
    }
}
