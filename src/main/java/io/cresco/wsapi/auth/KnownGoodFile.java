package io.cresco.wsapi.auth;

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
 * {@code ws.auth.known.key} (the HMAC key, 32 random bytes) and {@code ws.auth.known} (one {@code <id> <expiry ms>} line
 * per entry). Both are owner-only (0600); the ids are HMAC-SHA-256 of user and address under that key, so the file names
 * no user and no address. A file that is not owner-only, or unreadable, is not trusted: wsapi starts with an empty store
 * and a new key, and says so.
 */
public final class KnownGoodFile {

    static final String KEY_FILE = "ws.auth.known.key", ENTRIES_FILE = "ws.auth.known", HEADER = "cresco-wsapi-known-good 1";
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Where problems go (the plugin logger). */
    public interface Log {
        void warn(String m);
    }

    private final Path dir;
    private final Log log;

    public KnownGoodFile(Path dir, Log log) {
        this.dir = dir;
        this.log = log;
    }

    /** Load the key and entries into {@code limiter} (a new key, and an empty store, when there is none to trust). */
    public void load(AuthRateLimiter limiter) {
        byte[] key = null;
        Map<String, Long> entries = new LinkedHashMap<>();
        try {
            Path k = dir.resolve(KEY_FILE);
            if (Files.exists(k, LinkOption.NOFOLLOW_LINKS)) {
                if (!ownerOnly(k)) {
                    log.warn("wsapi: " + k + " is not owner-only (0600); not trusted, starting with no known-good sources");
                } else {
                    byte[] decoded = Base64.getDecoder().decode(Files.readString(k, StandardCharsets.US_ASCII).trim());
                    if (decoded.length == 32) key = decoded;
                }
            }
            Path e = dir.resolve(ENTRIES_FILE);
            if (key != null && Files.exists(e, LinkOption.NOFOLLOW_LINKS)) {
                if (!ownerOnly(e)) {
                    log.warn("wsapi: " + e + " is not owner-only (0600); not trusted, starting with no known-good sources");
                } else {
                    List<String> lines = Files.readAllLines(e, StandardCharsets.US_ASCII);
                    if (!lines.isEmpty() && HEADER.equals(lines.get(0))) {
                        for (String line : lines.subList(1, lines.size())) {
                            String[] f = line.trim().split(" ");
                            if (f.length == 2 && f[0].matches("[0-9a-f]{32}") && f[1].matches("[0-9]{1,19}")) {
                                entries.put(f[0], Long.parseLong(f[1]));
                            }
                        }
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("wsapi: cannot read the known-good sources (" + ex.getMessage() + "); starting with none");
            entries.clear();
            key = null;
        }
        if (key == null) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            entries.clear();
            try {
                write(dir.resolve(KEY_FILE), Base64.getEncoder().encodeToString(key) + "\n");
            } catch (IOException ex) {
                log.warn("wsapi: cannot write " + dir.resolve(KEY_FILE) + " (" + ex.getMessage() + "); known-good sources last "
                        + "until the next restart");
            }
        }
        limiter.useKnownGood(key, entries);
    }

    /** Write the entries when they changed since the last save. */
    public void saveIfChanged(AuthRateLimiter limiter) {
        Map<String, Long> entries = limiter.knownGoodIfChanged();
        if (entries == null) return;
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, Long> e : entries.entrySet()) sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        try {
            write(dir.resolve(ENTRIES_FILE), sb.toString());
        } catch (IOException ex) {
            log.warn("wsapi: cannot write the known-good sources (" + ex.getMessage() + ")");
        }
    }

    private static boolean ownerOnly(Path p) throws IOException {
        try {
            return OWNER_ONLY.containsAll(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS))
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException noPosix) {
            return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /** Owner-only from the start (created 0600 in a temporary file, then moved into place). */
    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException noPosix) {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, content, StandardCharsets.US_ASCII);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
