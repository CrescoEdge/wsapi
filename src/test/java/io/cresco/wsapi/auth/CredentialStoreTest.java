package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreTest {

    static final String HASH = PasswordHash.create("alice-password-1".toCharArray(), new SecureRandom()).encode();

    @Test
    void parsesUsersAndSkipsCommentsAndBlankLines() {
        CredentialStore s = CredentialStore.parse("# comment\n\nalice:tenant-a:admin:" + HASH + "\nbob@x.org:tenant-b:user:" + HASH + "\n", "f");
        assertEquals(2, s.size());
        assertEquals("tenant-a", s.get("alice").tenant);
        assertEquals("admin", s.get("alice").role);
        assertEquals("user", s.get("bob@x.org").role);
        assertTrue(s.get("alice").matches("alice-password-1".toCharArray()));
        assertNull(s.get("carol"));
        assertNull(s.get(null));
    }

    @Test
    void aBadLineRefusesTheWholeFileAndNamesTheLineNotItsContent() {
        String[] bad = {
                "alice:tenant-a:root:" + HASH,                      // role
                "alice:tenant.a:admin:" + HASH,                     // tenant grammar (no '.')
                "al ice:tenant-a:admin:" + HASH,                    // user grammar
                "service-key:tenant-a:admin:" + HASH,               // reserved
                "alice:tenant-a:admin",                             // fields
                "alice:tenant-a:admin:pbkdf2-sha384$1000$AAAA$BBBB", // weak hash
        };
        for (String line : bad) {
            CredentialStore.Invalid e = assertThrows(CredentialStore.Invalid.class,
                    () -> CredentialStore.parse("bob:tenant-a:user:" + HASH + "\n" + line + "\n", "ws.users"), line);
            assertTrue(e.getMessage().startsWith("ws.users line 2"), e.getMessage());
            assertFalse(e.getMessage().contains(HASH.split("\\$")[3]), "the error must not repeat a hash");
        }
        assertThrows(CredentialStore.Invalid.class, () -> CredentialStore.parse("a:t:user:" + HASH + "\na:t:admin:" + HASH, "f"));
    }

    @Test
    void streamGrantsAreParsedValidatedAndManagedByTheTool(@TempDir Path dir) throws Exception {
        CredentialStore s = CredentialStore.parse("bob:tenant-a:user:" + HASH + ":job-7,team/*\n", "f");
        assertEquals(java.util.List.of("job-7", "team/*"), s.get("bob").grants);
        assertThrows(CredentialStore.Invalid.class, () -> CredentialStore.parse("bob:tenant-a:user:" + HASH + ":bad grant\n", "f"));
        Path f = dir.resolve("ws.users");
        CredentialTool.PasswordSource pw = prompt -> "first-password-1".toCharArray();
        PrintStream o = new PrintStream(new ByteArrayOutputStream()), e = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, CredentialTool.run(new String[]{"add", f.toString(), "bob", "tenant-a", "user"}, o, e, pw));
        assertEquals(0, CredentialTool.run(new String[]{"grant", f.toString(), "bob", "job-7"}, o, e, pw));
        assertEquals(0, CredentialTool.run(new String[]{"grant", f.toString(), "bob", "team/*"}, o, e, pw));
        assertEquals(java.util.List.of("job-7", "team/*"), CredentialTool.load(f).get("bob").grants);
        assertEquals(0, CredentialTool.run(new String[]{"passwd", f.toString(), "bob"}, o, e, pw));
        assertEquals(java.util.List.of("job-7", "team/*"), CredentialTool.load(f).get("bob").grants, "a new password keeps the grants");
        assertEquals(0, CredentialTool.run(new String[]{"revoke", f.toString(), "bob", "job-7"}, o, e, pw));
        assertEquals(java.util.List.of("team/*"), CredentialTool.load(f).get("bob").grants);
        assertEquals(1, CredentialTool.run(new String[]{"revoke", f.toString(), "bob", "job-7"}, o, e, pw));
        assertEquals(1, CredentialTool.run(new String[]{"grant", f.toString(), "bob", "x y"}, o, e, pw));
    }

    @Test
    void theToolAddsChangesListsAndRemovesUsersInAnOwnerOnlyFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ws.users");
        String[] next = {"first-password-1"};
        CredentialTool.PasswordSource pw = prompt -> next[0].toCharArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out), e = new PrintStream(err);

        assertEquals(0, CredentialTool.run(new String[]{"add", f.toString(), "alice", "tenant-a", "admin"}, o, e, pw));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        assertTrue(CredentialTool.load(f).get("alice").matches("first-password-1".toCharArray()));
        assertFalse(Files.readString(f).contains("first-password-1"));

        assertEquals(1, CredentialTool.run(new String[]{"add", f.toString(), "alice", "tenant-a", "admin"}, o, e, pw), "exists");
        next[0] = "second-password-2";
        assertEquals(0, CredentialTool.run(new String[]{"passwd", f.toString(), "alice"}, o, e, pw));
        assertTrue(CredentialTool.load(f).get("alice").matches("second-password-2".toCharArray()));
        assertEquals("tenant-a", CredentialTool.load(f).get("alice").tenant);

        next[0] = "short";
        assertEquals(1, CredentialTool.run(new String[]{"add", f.toString(), "bob", "tenant-a", "user"}, o, e, pw), "too short");
        assertEquals(1, CredentialTool.run(new String[]{"add", f.toString(), "bob", "tenant-a", "root"}, o, e, pw), "bad role");

        out.reset();
        assertEquals(0, CredentialTool.run(new String[]{"list", f.toString()}, o, e, pw));
        assertTrue(out.toString().contains("alice (tenant-a, admin)"));
        assertFalse(out.toString().contains("pbkdf2"));

        assertEquals(0, CredentialTool.run(new String[]{"remove", f.toString(), "alice"}, o, e, pw));
        assertEquals(0, CredentialTool.load(f).size());
        assertEquals(2, CredentialTool.run(new String[]{"frobnicate"}, o, e, pw));
    }
}
