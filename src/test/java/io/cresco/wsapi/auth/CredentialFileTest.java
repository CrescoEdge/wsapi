package io.cresco.wsapi.auth;

import io.cresco.wsapi.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CredentialFileTest {

    static final String HASH = PasswordHash.create("alice-password-1".toCharArray(), new SecureRandom()).encode();

    @Test
    void theFileIsReReadWhenItChangesAndABadOneLocksEveryoneOut(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ws.users");
        AtomicLong now = new AtomicLong(0);
        List<String> log = new ArrayList<>();
        CredentialFile cf = new CredentialFile(f, TestSupport.logger(log), now::get);
        assertEquals(0, cf.current().size(), "missing file: no users");

        Files.writeString(f, "alice:tenant-a:admin:" + HASH + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        now.addAndGet(1500);
        assertEquals(1, cf.current().size(), String.join("\n", log));

        Files.writeString(f, "alice:tenant-a:admin:" + HASH + "\nbob:tenant-a:user:" + HASH + "\n");
        now.addAndGet(500);
        assertEquals(1, cf.current().size(), "checked at most once a second");
        now.addAndGet(600);
        assertEquals(2, cf.current().size());

        Files.writeString(f, "alice:tenant-a:superadmin:" + HASH + "\n");
        now.addAndGet(1500);
        assertEquals(0, cf.current().size(), "a store that does not parse leaves no user able to log in");
        assertTrue(log.stream().anyMatch(l -> l.startsWith("ERROR") && l.contains("line 1")));
        assertTrue(log.stream().noneMatch(l -> l.contains(HASH)));

        Files.writeString(f, "alice:tenant-a:admin:" + HASH + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        now.addAndGet(1500);
        assertEquals(0, cf.current().size(), "a file others can read is refused");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(f, "alice:tenant-a:admin:" + HASH + "\n\n");
        now.addAndGet(1500);
        assertEquals(1, cf.current().size());
    }
}
