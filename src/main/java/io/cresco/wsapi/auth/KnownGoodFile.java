package io.cresco.wsapi.auth;

import io.cresco.library.security.OwnerOnlyFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the {@link AuthRateLimiter}'s known-good sources across restarts, in the wsapi data directory:
 * {@code ws.auth.known.key} (the HMAC key, 32 random bytes) and {@code ws.auth.known} (one {@code <user id> <id> <expiry ms>}
 * line per entry, ids being HMAC-SHA-256 under that key, so the file names no user and no address).
 *
 * <p>Both are read only as the library's {@link OwnerOnlyFile} reads secrets: a regular file owned by the JVM user, mode
 * 0600 or 0400, in a directory no one else can write, on a filesystem with POSIX owner and mode (one without them is
 * refused, never trusted). A file that fails, or does not parse, is not trusted: wsapi starts with no known-good
 * sources and a new key, and says so. Where the files cannot be written owner-only, known-good sources last until the
 * next restart.
 */
public final class KnownGoodFile {

    static final String KEY_FILE = "ws.auth.known.key", ENTRIES_FILE = "ws.auth.known", HEADER = "cresco-wsapi-known-good 2";
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Where problems go (the plugin logger). */
    public interface Log {
        void warn(String m);
    }

    private final Path dir;
    private final Log log;
    private volatile boolean writable = true;

    public KnownGoodFile(Path dir, Log log) {
        this.dir = dir;
        this.log = log;
    }

    /** Load the key and entries into {@code limiter} (a new key, and an empty store, when there is none to trust). */
    public void load(AuthRateLimiter limiter) {
        byte[] key = null;
        Map<String, Map<String, Long>> entries = new LinkedHashMap<>();
        Path k = dir.resolve(KEY_FILE), e = dir.resolve(ENTRIES_FILE);
        try {
            if (Files.exists(k, LinkOption.NOFOLLOW_LINKS)) {
                byte[] decoded = Base64.getDecoder().decode(OwnerOnlyFile.read(k, "the known-good key").trim());
                if (decoded.length == 32) key = decoded;
            }
            if (key != null && Files.exists(e, LinkOption.NOFOLLOW_LINKS)) {
                List<String> lines = OwnerOnlyFile.read(e, "the known-good sources").lines().toList();
                if (!lines.isEmpty() && HEADER.equals(lines.get(0))) {
                    for (String line : lines.subList(1, lines.size())) {
                        String[] f = line.trim().split(" ");
                        if (f.length == 3 && f[0].matches("[0-9a-f]{32}") && f[1].matches("[0-9a-f]{32}") && f[2].matches("[0-9]{1,19}")) {
                            entries.computeIfAbsent(f[0], x -> new LinkedHashMap<>()).put(f[1], Long.parseLong(f[2]));
                        }
                    }
                } else if (!lines.isEmpty()) {
                    log.warn("wsapi: " + e + " is an older format; starting with no known-good sources");
                }
            }
        } catch (RuntimeException ex) {                   // OwnerOnlyFile.UnsafeFileException, bad base64, unreadable
            log.warn("wsapi: the known-good sources are not trusted (" + ex.getMessage() + "); starting with none");
            entries.clear();
            key = null;
        }
        if (key == null) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            entries.clear();
            write(k, Base64.getEncoder().encodeToString(key) + "\n");
        }
        limiter.useKnownGood(key, entries);
    }

    /** Write the entries when they changed since the last save. */
    public void saveIfChanged(AuthRateLimiter limiter) {
        Map<String, Map<String, Long>> entries = limiter.knownGoodIfChanged();
        if (entries == null) return;
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, Map<String, Long>> u : entries.entrySet()) {
            for (Map.Entry<String, Long> en : u.getValue().entrySet()) {
                sb.append(u.getKey()).append(' ').append(en.getKey()).append(' ').append(en.getValue()).append('\n');
            }
        }
        write(dir.resolve(ENTRIES_FILE), sb.toString());
    }

    /** Owner-only from the start (created 0600, then moved into place); never on a filesystem without POSIX modes. */
    private void write(Path target, String content) {
        if (!writable) return;
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            Files.writeString(tmp, content, StandardCharsets.US_ASCII);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException noPosix) {
            writable = false;
            log.warn("wsapi: " + dir + " has no POSIX permissions, so the known-good sources are not written there; they last "
                    + "until the next restart");
        } catch (IOException ex) {
            log.warn("wsapi: cannot write " + target + " (" + ex.getMessage() + ")");
        }
    }
}
