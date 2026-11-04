package io.cresco.wsapi.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * wsapi's per-user credentials: each user belongs to one tenant and holds one role, {@code admin} or {@code user}.
 *
 * <p>The file has one user per line, {@code user:tenant:role:hash[:grants]}, where hash is a {@link PasswordHash} and
 * grants (optional) is a comma list of dataplane stream names the user may use besides their own {@code u:<user>:...}
 * streams (a trailing {@code *} grants a prefix); blank lines and lines starting with {@code #} are ignored. Manage it with {@link CredentialTool}. A malformed line is refused
 * as a whole file (fail closed): a store with an error is never half-loaded, and the error names the line number,
 * never its content. Immutable.
 */
public final class CredentialStore {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";
    /** The name the legacy service key authenticates as; no stored user may take it. */
    public static final String SERVICE_KEY_USER = "service-key";

    /** API user names: 1-128 of {@code A-Z a-z 0-9 . _ @ -} (the Cresco API-caller name grammar). */
    static final Pattern USER = Pattern.compile("[A-Za-z0-9._@-]{1,128}");
    /** Cresco tenant names (CrescoIdentity.isValidTenant): 1-64 of {@code A-Z a-z 0-9 -}, alphanumeric at both ends. */
    static final Pattern TENANT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?");

    /** A grantable stream name or prefix: the dataplane value grammar, optionally ending in {@code *}. */
    static final Pattern GRANT = Pattern.compile("[A-Za-z0-9_.:@/+\\-]{1,256}\\*?");

    /** One user. */
    public static final class Entry {
        public final String user;
        public final String tenant;
        public final String role;
        /** Dataplane streams granted to the user besides their own (exact names, or prefixes ending in *). */
        public final List<String> grants;
        final PasswordHash hash;

        Entry(String user, String tenant, String role, PasswordHash hash) {
            this(user, tenant, role, hash, List.of());
        }

        Entry(String user, String tenant, String role, PasswordHash hash, List<String> grants) {
            this.user = user;
            this.tenant = tenant;
            this.role = role;
            this.hash = hash;
            this.grants = List.copyOf(grants);
        }

        public boolean matches(char[] secret) { return hash.matches(secret); }

        String line() {
            return user + ":" + tenant + ":" + role + ":" + hash.encode() + (grants.isEmpty() ? "" : ":" + String.join(",", grants));
        }

        @Override
        public String toString() {
            return user + " (" + tenant + ", " + role + (grants.isEmpty() ? "" : ", streams " + String.join(",", grants)) + ")";
        }
    }

    /** A store that cannot be loaded; the message names the line, never its content. */
    public static final class Invalid extends IllegalArgumentException {
        Invalid(String msg) { super(msg); }
    }

    private final Map<String, Entry> users;

    private CredentialStore(Map<String, Entry> users) {
        this.users = Collections.unmodifiableMap(users);
    }

    public static CredentialStore empty() { return new CredentialStore(new LinkedHashMap<>()); }

    /** Parse a store; {@code source} names it in errors. */
    public static CredentialStore parse(String content, String source) {
        Map<String, Entry> m = new LinkedHashMap<>();
        if (content == null) return new CredentialStore(m);
        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String where = source + " line " + (i + 1);
            String[] f = line.split(":", 5);
            if (f.length < 4) throw new Invalid(where + ": expected user:tenant:role:hash[:grants]");
            validate(f[0], f[1], f[2], where);
            PasswordHash h;
            try {
                h = PasswordHash.parse(f[3]);
            } catch (IllegalArgumentException e) {
                throw new Invalid(where + ": " + e.getMessage());
            }
            List<String> grants = new ArrayList<>();
            if (f.length == 5 && !f[4].isBlank()) {
                for (String g : f[4].split(",")) {
                    String t = g.strip();
                    if (!GRANT.matcher(t).matches()) throw new Invalid(where + ": a stream grant must match " + GRANT.pattern());
                    grants.add(t);
                }
            }
            if (m.containsKey(f[0])) throw new Invalid(where + ": user " + f[0] + " appears twice");
            m.put(f[0], new Entry(f[0], f[1], f[2], h, grants));
        }
        return new CredentialStore(m);
    }

    static void validate(String user, String tenant, String role, String where) {
        if (!USER.matcher(user).matches()) throw new Invalid(where + ": user names are 1-128 of A-Z a-z 0-9 . _ @ -");
        if (SERVICE_KEY_USER.equals(user)) throw new Invalid(where + ": the user name " + SERVICE_KEY_USER + " is reserved for the service key");
        if (!TENANT.matcher(tenant).matches()) throw new Invalid(where + ": tenant " + tenant + " is not a valid Cresco tenant name");
        if (!ROLE_ADMIN.equals(role) && !ROLE_USER.equals(role)) throw new Invalid(where + ": role must be admin or user");
    }

    /** The user, or null. */
    public Entry get(String user) { return user == null ? null : users.get(user); }

    public int size() { return users.size(); }

    public List<Entry> entries() { return new ArrayList<>(users.values()); }

    /** A copy with {@code e} added or replaced. */
    CredentialStore with(Entry e) {
        Map<String, Entry> m = new LinkedHashMap<>(users);
        m.put(e.user, e);
        return new CredentialStore(m);
    }

    /** A copy without {@code user}. */
    CredentialStore without(String user) {
        Map<String, Entry> m = new LinkedHashMap<>(users);
        m.remove(user);
        return new CredentialStore(m);
    }

    /** The file form. */
    String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Cresco wsapi credentials: user:tenant:role:pbkdf2-sha384$iterations$salt$hash[:stream grants]\n");
        sb.append("# Manage with: java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool help\n");
        for (Entry e : users.values()) sb.append(e.line()).append('\n');
        return sb.toString();
    }
}
