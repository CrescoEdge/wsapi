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
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool role   &lt;file&gt; &lt;user&gt; &lt;admin|user&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool grant  &lt;file&gt; &lt;user&gt; &lt;stream|prefix*&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool revoke &lt;file&gt; &lt;user&gt; &lt;stream|prefix*&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool list   &lt;file&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool token add    &lt;file&gt; &lt;user&gt; [--expires-days N] [--label L]
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool token list   &lt;file&gt;
 *   java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool token revoke &lt;file&gt; &lt;token id&gt;
 * </pre>
 * Passwords must meet the {@link PasswordPolicy}. An API token (for automation) is printed once, when it is made; only its
 * SHA-384 is kept.
 * The password is read from the terminal without echo (twice), or, with no terminal, as the first line of standard
 * input. It is never taken from the command line (where {@code ps} would show it). The file is written atomically with
 * mode 0600. wsapi re-reads it within seconds of a change, and closes the open connections a change revokes (a removed
 * user, a changed password, role or grants, a revoked token).
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
                        save(file, store.with(new CredentialStore.Entry(e.user, e.tenant, e.role, PasswordHash.create(secret, new SecureRandom()), e.grants)));
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
                case "role": {
                    need(args, 4);
                    Path file = Paths.get(args[1]);
                    CredentialStore store = load(file);
                    CredentialStore.Entry e = store.get(args[2]);
                    if (e == null) throw new IllegalArgumentException("no user " + args[2]);
                    CredentialStore.validate(e.user, e.tenant, args[3], "user");
                    save(file, store.with(new CredentialStore.Entry(e.user, e.tenant, args[3], e.hash, e.grants)));
                    out.println("set the role of " + args[2] + " to " + args[3] + " in " + file);
                    return 0;
                }
                case "grant":
                case "revoke": {
                    need(args, 4);
                    Path file = Paths.get(args[1]);
                    CredentialStore store = load(file);
                    CredentialStore.Entry e = store.get(args[2]);
                    if (e == null) throw new IllegalArgumentException("no user " + args[2]);
                    String g = args[3];
                    if (!CredentialStore.GRANT.matcher(g).matches()) throw new IllegalArgumentException("a stream grant must match " + CredentialStore.GRANT.pattern());
                    java.util.List<String> grants = new java.util.ArrayList<>(e.grants);
                    if (cmd.equals("grant")) {
                        if (!grants.contains(g)) grants.add(g);
                    } else if (!grants.remove(g)) {
                        throw new IllegalArgumentException(args[2] + " has no grant " + g);
                    }
                    save(file, store.with(new CredentialStore.Entry(e.user, e.tenant, e.role, e.hash, grants)));
                    out.println((cmd.equals("grant") ? "granted " : "revoked ") + g + (cmd.equals("grant") ? " to " : " from ") + args[2] + " in " + file);
                    return 0;
                }
                case "list": {
                    need(args, 2);
                    CredentialStore store = load(Paths.get(args[1]));
                    for (CredentialStore.Entry e : store.entries()) out.println(e);
                    for (CredentialStore.Token t : store.tokens()) out.println("token " + t);
                    return 0;
                }
                case "token":
                    return token(args, out);
                default:
                    usage(err);
                    return 2;
            }
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** {@code token add|list|revoke ...}. */
    private static int token(String[] args, PrintStream out) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("token add|list|revoke <file> ...; see help");
        Path file = Paths.get(args[2]);
        CredentialStore store = load(file);
        switch (args[1]) {
            case "add": {
                if (args.length < 4) throw new IllegalArgumentException("token add <file> <user> [--expires-days N] [--label L]");
                String user = args[3];
                if (store.get(user) == null) throw new IllegalArgumentException("no user " + user);
                long expiresDays = 0;
                String label = "";
                for (int i = 4; i < args.length; i++) {
                    if ("--expires-days".equals(args[i]) && i + 1 < args.length) {
                        expiresDays = Long.parseLong(args[++i]);
                        if (expiresDays < 1 || expiresDays > 3650) throw new IllegalArgumentException("--expires-days takes 1 to 3650");
                    } else if ("--label".equals(args[i]) && i + 1 < args.length) {
                        label = args[++i];
                        if (!CredentialStore.Token.LABEL.matcher(label).matches()) {
                            throw new IllegalArgumentException("a label is up to 64 of A-Z a-z 0-9 . _ space -");
                        }
                    } else {
                        throw new IllegalArgumentException("unknown option " + args[i]);
                    }
                }
                SecureRandom r = new SecureRandom();
                byte[] idBytes = new byte[8], secret = new byte[32];
                r.nextBytes(idBytes);
                r.nextBytes(secret);
                StringBuilder id = new StringBuilder();
                for (byte b : idBytes) id.append(String.format("%02x", b & 0xff));
                String tok = "cwt1_" + id + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
                long now = System.currentTimeMillis() / 1000L;
                CredentialStore.Token t = new CredentialStore.Token(id.toString(), user, CredentialStore.Token.hash(tok), now,
                        expiresDays > 0 ? now + expiresDays * 86400L : 0L, label);
                save(file, store.withToken(t));
                out.println(tok);
                System.err.println("token " + t + " added to " + file + "; it is shown only now. Give it to the tool as "
                        + "CRESCO_API_TOKEN (or the clients' token option); revoke it with: token revoke " + file + " " + id);
                return 0;
            }
            case "list": {
                for (CredentialStore.Token t : store.tokens()) out.println(t);
                return 0;
            }
            case "revoke": {
                if (args.length != 4) throw new IllegalArgumentException("token revoke <file> <token id>");
                if (store.token(args[3]) == null) throw new IllegalArgumentException("no token " + args[3]);
                save(file, store.withoutToken(args[3]));
                out.println("revoked token " + args[3] + " in " + file);
                return 0;
            }
            default:
                throw new IllegalArgumentException("token add|list|revoke; see help");
        }
    }

    private static void need(String[] args, int n) {
        if (args.length != n) throw new IllegalArgumentException("wrong number of arguments; see help");
    }

    private static char[] newPassword(PasswordSource pw, String user) {
        char[] a = pw.read("New password for " + user + ": ");
        if (a == null) throw new IllegalArgumentException("no password read");
        String weak = PasswordPolicy.weakness(user, a);
        if (weak != null) {
            Arrays.fill(a, '\0');
            throw new IllegalArgumentException("that password is refused: " + weak + " (the policy: at least "
                    + PasswordPolicy.MIN_CHARS + " characters, about " + PasswordPolicy.MIN_BITS + " bits, not a well-known password)");
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
        out.println("  remove <file> <user>                         remove a user (and its API tokens)");
        out.println("  role   <file> <user> <admin|user>            change a user's role");
        out.println("  grant  <file> <user> <stream|prefix*>         let a user use a dataplane stream besides their own u:<user>:*");
        out.println("  revoke <file> <user> <stream|prefix*>         take that back");
        out.println("  list   <file>                                list users and API tokens (never passwords or tokens)");
        out.println("  token add    <file> <user> [--expires-days N] [--label L]   make an API token for automation (printed once)");
        out.println("  token list   <file>                                          list tokens (ids, users, labels, expiry)");
        out.println("  token revoke <file> <token id>                               revoke a token");
        out.println("Passwords need at least " + PasswordPolicy.MIN_CHARS + " characters and about " + PasswordPolicy.MIN_BITS
                + " bits (e.g. four random words), and may not be a well-known password.");
        out.println("The default file is <agent data>/plugin-data/<wsapi plugin id>/ws.users (wsapi_users_file overrides it).");
        out.println("Without a terminal the password is the first line of standard input.");
        out.println("A running wsapi applies a change within about 2 s, also to open connections: removing a user, changing its");
        out.println("password, role or grants, or revoking a token closes the connections that change revokes.");
    }
}
