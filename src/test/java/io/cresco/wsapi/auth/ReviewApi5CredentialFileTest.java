package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 5 (review/api-20261104): the credential file is trusted only while owner-only, and a refused
 * file "leaves no user able to log in" and now closes every per-user connection. But the file is re-read only when its
 * modification time, size or file key change; a permission change (chmod 644, a backup tool restoring modes, a
 * group-readable deploy) changes none of them. Live: after chmod 644 the open per-user connection stayed open and logins
 * kept working, from a file every local user could read, until its content next changed.
 */
class ReviewApi5CredentialFileTest {

    @Test
    void aCredentialFileThatStopsBeingOwnerOnlyIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("wsapi-review-cred");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path file = dir.resolve("ws.users");
        CredentialTool.save(file, CredentialStore.empty().with(new CredentialStore.Entry("bob", "tenant-a", "user",
                PasswordHash.create("bob-Password-q7w9x2".toCharArray(), new SecureRandom()))));
        AtomicLong now = new AtomicLong(1_000_000L);
        CredentialFile cf = new CredentialFile(file, null, now::get);
        assertEquals(1, cf.current().size(), "control: the owner-only file is loaded");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        now.addAndGet(5_000);
        assertEquals(0, cf.current().size(), "a world-readable credential file is still trusted after the re-check interval");
    }
}
