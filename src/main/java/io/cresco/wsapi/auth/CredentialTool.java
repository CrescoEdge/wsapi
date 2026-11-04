package io.cresco.wsapi.auth;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Manage a wsapi credential file from the command line. Runs from the wsapi jar alone:
 * <pre>
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool add    &lt;file&gt; &lt;user&gt; &lt;tenant&gt; &lt;admin|user&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool passwd &lt;file&gt; &lt;user&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool remove &lt;file&gt; &lt;user&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool list   &lt;file&gt;
 * </pre>
 * The password is read from the terminal without echo (twice), or, with no terminal, as the first line of standard
 * input. It is never taken from the command line (where {@code ps} would show it). The file is written atomically with
 * mode 0600. wsapi re-reads it within seconds of a change.
 */
public final class CredentialTool {

    private CredentialTool() {}

    public static void main(String[] args) {
        int rc = run(args, System.out, System.err, CredentialTool::readPassword);
        if (rc != 0) System.exit(rc);
    }

    /** Reads a new password; null when none could be read. */
    interface PasswordSource { char[] read(String prompt); }

    static int run(String[] args, PrintStream out, PrintStream err, PasswordSource pw) {
        if (args.length == 0 || "help".equals(args[0]) || "-h".equals(args[0]) || "--help".equals(args[0])) {
            usage(out);
            return args.length == 0 ? 2 : 0;
        }
        try {
            String cmd = args[0];
            switch (cmd) {
                case "add": {
                    need(args, 5);
                    Path file = Paths.get(args[1]);
                    CredentialStore store = load(file);
                    if (store.get(args[2]) != null) throw new IllegalArgumentException("user " + args[2] + " exists; use passwd");
                    CredentialStore.validate(args[2], args[3], args[4], "user");
                    char[] secret = newPassword(pw, args[2]);
                    try {
                        PasswordHash h = PasswordHash.create(secret, new SecureRandom());
                        save(file, store.with(new CredentialStore.Entry(args[2], args[3], args[4], h)));
                    } finally {
                        Arrays.fill(secret, '\0');
                    }
                    out.println("added " + args[2] + " (tenant " + args[3] + ", role " + args[4] + ") to " + file);
                    return 0;
                }
                case "passwd": {
                    need(args, 3);
                    Path file = Paths.get(args[1]);
                    CredentialStore store = load(file);
                    CredentialStore.Entry e = store.get(args[2]);
                    if (e == null) throw new IllegalArgumentException("no user " + args[2]);
                    char[] secret = newPassword(pw, args[2]);
                    try {
                        save(file, store.with(new CredentialStore.Entry(e.user, e.tenant, e.role, PasswordHash.create(secret, new SecureRandom()))));
                    } finally {
                        Arrays.fill(secret, '\0');
                    }
                    out.println("changed the password of " + args[2] + " in " + file);
                    return 0;
                }
                case "remove": {
                    need(args, 3);
                    Path file = Paths.get(args[1]);
                    CredentialStore store = load(file);
                    if (store.get(args[2]) == null) throw new IllegalArgumentException("no user " + args[2]);
                    save(file, store.without(args[2]));
                    out.println("removed " + args[2] + " from " + file);
                    return 0;
                }
                case "list": {
                    need(args, 2);
                    for (CredentialStore.Entry e : load(Paths.get(args[1])).entries()) out.println(e);
                    return 0;
                }
                default:
                    usage(err);
                    return 2;
            }
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static void need(String[] args, int n) {
        if (args.length != n) throw new IllegalArgumentException("wrong number of arguments; see help");
    }

    private static char[] newPassword(PasswordSource pw, String user) {
        char[] a = pw.read("New password for " + user + ": ");
        if (a == null) throw new IllegalArgumentException("no password read");
        if (a.length < PasswordHash.MIN_PASSWORD_CHARS) {
            Arrays.fill(a, '\0');
            throw new IllegalArgumentException("passwords need at least " + PasswordHash.MIN_PASSWORD_CHARS + " characters");
        }
        if (System.console() != null) {
            char[] b = pw.read("Repeat it: ");
            boolean same = b != null && Arrays.equals(a, b);
            if (b != null) Arrays.fill(b, '\0');
            if (!same) {
                Arrays.fill(a, '\0');
                throw new IllegalArgumentException("the passwords differ");
            }
        }
        return a;
    }

    private static char[] readPassword(String prompt) {
        Console c = System.console();
        if (c != null) return c.readPassword("%s", prompt);
        try {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            return line == null ? null : line.toCharArray();
        } catch (IOException e) {
            return null;
        }
    }

    static CredentialStore load(Path file) throws IOException {
        if (!Files.exists(file)) return CredentialStore.empty();
        return CredentialStore.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.toString());
    }

    /** Write atomically: a 0600 temp file in the same directory, then a rename over the old one. */
    static void save(Path file, CredentialStore store) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".ws.users", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException noPosix) {
            throw new IOException("this filesystem has no POSIX permissions; the credential file must be mode 0600");
        }
        try {
            Files.write(tmp, store.serialize().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void usage(PrintStream out) {
        out.println("Manage wsapi per-user credentials (PBKDF2-HMAC-SHA-384, file mode 0600).");
        out.println("  add    <file> <user> <tenant> <admin|user>   add a user (asks for the password)");
        out.println("  passwd <file> <user>                         set a new password");
        out.println("  remove <file> <user>                         remove a user");
        out.println("  list   <file>                                list users (never passwords)");
        out.println("The default file is <agent data>/plugin-data/<wsapi plugin id>/ws.users (wsapi_users_file overrides it).");
        out.println("Without a terminal the password is the first line of standard input.");
    }
}
