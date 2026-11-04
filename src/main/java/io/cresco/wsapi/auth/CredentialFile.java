package io.cresco.wsapi.auth;

import io.cresco.library.security.OwnerOnlyFile;
import io.cresco.library.utilities.CLogger;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The wsapi credential file, re-read when it changes (checked at most once a second), so users can be added, changed
 * and removed without restarting wsapi.
 *
 * <p>The file is read only when owner-only ({@link OwnerOnlyFile}: a regular file owned by the JVM user, mode 0600 or
 * 0400, in a directory no one else can write). A file that fails that check or does not parse is an ERROR and
 * leaves no user able to log in (fail closed) until it is fixed; the service key, if enabled, still works. A missing
 * file means no users.
 */
public final class CredentialFile {

    private final Path path;
    private final CLogger logger;
    private final LongSupplier clock;
    private volatile CredentialStore current = CredentialStore.empty();
    private volatile Object stamp = "unread";
    private volatile long lastCheck;

    public CredentialFile(Path path, CLogger logger, LongSupplier clock) {
        this.path = path;
        this.logger = logger;
        this.clock = clock;
        this.lastCheck = clock.getAsLong();
        reload(true);
    }

    public Path path() { return path; }

    /** The store as of the file's last change (re-checked at most once a second). */
    public CredentialStore current() {
        long now = clock.getAsLong();
        if (due(now)) {
            synchronized (this) {
                if (due(now)) {
                    lastCheck = now;
                    reload(false);
                }
            }
        }
        return current;
    }

    private boolean due(long now) {
        long since = now - lastCheck;
        return since >= 1000L || since < 0;          // a clock that went back also re-checks
    }

    private void reload(boolean first) {
        Object s = stampOf();
        if (!first && Objects.equals(s, stamp)) return;
        stamp = s;
        if (s == null) {
            current = CredentialStore.empty();
            if (logger != null) logger.info("wsapi credential file " + path + " does not exist: no per-user credentials");
            return;
        }
        try {
            CredentialStore next = CredentialStore.parse(OwnerOnlyFile.read(path, "wsapi_users_file"), path.toString());
            current = next;
            if (logger != null) logger.info("wsapi credential file " + path + " loaded: " + next.size() + " user(s)");
        } catch (RuntimeException e) {
            current = CredentialStore.empty();
            if (logger != null) logger.error("wsapi credential file " + path + " refused, so no user can log in until it is fixed: " + e.getMessage());
        }
    }

    private Object stampOf() {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.lastModifiedTime().toMillis() + "/" + a.size() + "/" + a.fileKey();
        } catch (Exception e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }
}
