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
 * streams (a trailing {@code *} grants a prefix); blank lines and lines starting with {@code #} are ignored. A line {@code +token:<id>:<user>:<sha384>:<created>:<expires>:<label>} is an API token of that user ({@link Token}). Manage it with {@link CredentialTool}. A malformed line is refused
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

    /**
     * An API token of a user: {@code cwt1_<id>.<secret>}, 256 random bits of secret, for automation. Only its SHA-384 is
     * stored; it is revoked by removing it, and may expire. Tokens cannot be guessed, so they are limited per source
     * only, never per user: a tool that uses one is never locked out by strangers guessing the user's password.
     */
    public static final class Token {
        public static final java.util.regex.Pattern FORM = java.util.regex.Pattern.compile("cwt1_([0-9a-f]{16})\\.([A-Za-z0-9_-]{43})");
        static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile("[A-Za-z0-9._ -]{0,64}");
        public final String id, user, label;
        final String sha384;
        public final long createdSeconds, expiresSeconds;            // expires 0: never

        Token(String id, String user, String sha384, long createdSeconds, long expiresSeconds, String label) {
            this.id = id;
            this.user = user;
            this.sha384 = sha384;
            this.createdSeconds = createdSeconds;
            this.expiresSeconds = expiresSeconds;
            this.label = label == null ? "" : label;
        }

        public boolean expired(long nowMs) { return expiresSeconds > 0 && nowMs / 1000L >= expiresSeconds; }

        /** The SHA-384 a presented token must have. */
        static String hash(String token) {
            try {
                byte[] d = java.security.MessageDigest.getInstance("SHA-384").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b & 0xff));
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean matches(String token) {
            return java.security.MessageDigest.isEqual(sha384.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    hash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        String line() {
            return "+token:" + id + ":" + user + ":" + sha384 + ":" + createdSeconds + ":" + expiresSeconds + ":" + label;
        }

        @Override
        public String toString() {
            return id + " of " + user + (label.isEmpty() ? "" : " (" + label + ")") + ", created "
                    + java.time.Instant.ofEpochSecond(createdSeconds) + (expiresSeconds > 0 ? ", expires "
                    + java.time.Instant.ofEpochSecond(expiresSeconds) : ", no expiry");
        }
    }

    /** A store that cannot be loaded; the message names the line, never its content. */
    public static final class Invalid extends IllegalArgumentException {
        Invalid(String msg) { super(msg); }
    }

    private final Map<String, Entry> users;
    private final Map<String, Token> tokens;

    private CredentialStore(Map<String, Entry> users) {
        this(users, new LinkedHashMap<>());
    }

    private CredentialStore(Map<String, Entry> users, Map<String, Token> tokens) {
        this.users = Collections.unmodifiableMap(users);
        this.tokens = Collections.unmodifiableMap(tokens);
    }

    public static CredentialStore empty() { return new CredentialStore(new LinkedHashMap<>()); }

    /** The API token with this id, or null. */
    public Token token(String id) { return id == null ? null : tokens.get(id); }

    public List<Token> tokens() { return new ArrayList<>(tokens.values()); }

    /** A copy with {@code t} added (its user must exist). */
    CredentialStore withToken(Token t) {
        if (!users.containsKey(t.user)) throw new IllegalArgumentException("no user " + t.user);
        Map<String, Token> m = new LinkedHashMap<>(tokens);
        m.put(t.id, t);
        return new CredentialStore(new LinkedHashMap<>(users), m);
    }

    /** A copy without the token {@code id}. */
    CredentialStore withoutToken(String id) {
        Map<String, Token> m = new LinkedHashMap<>(tokens);
        m.remove(id);
        return new CredentialStore(new LinkedHashMap<>(users), m);
    }

    /** Parse a store; {@code source} names it in errors. */
    public static CredentialStore parse(String content, String source) {
        Map<String, Entry> m = new LinkedHashMap<>();
        Map<String, Token> t = new LinkedHashMap<>();
        if (content == null) return new CredentialStore(m);
        String[] lines = content.split("\r?\n", -1);
        List<String> tokenWhere = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String where = source + " line " + (i + 1);
            if (line.startsWith("+token:")) {
                String[] f = line.split(":", 7);
                if (f.length != 7 || !f[1].matches("[0-9a-f]{16}") || !USER.matcher(f[2]).matches() || !f[3].matches("[0-9a-f]{96}")
                        || !f[4].matches("[0-9]{1,19}") || !f[5].matches("[0-9]{1,19}") || !Token.LABEL.matcher(f[6]).matches()) {
                    throw new Invalid(where + ": expected +token:<16 hex id>:<user>:<96 hex sha384>:<created>:<expires>:<label>");
                }
                if (t.containsKey(f[1])) throw new Invalid(where + ": token " + f[1] + " appears twice");
                t.put(f[1], new Token(f[1], f[2], f[3], Long.parseLong(f[4]), Long.parseLong(f[5]), f[6]));
                tokenWhere.add(where);
                continue;
            }
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
                    String gr = g.strip();
                    if (!GRANT.matcher(gr).matches()) throw new Invalid(where + ": a stream grant must match " + GRANT.pattern());
                    grants.add(gr);
                }
            }
            if (m.containsKey(f[0])) throw new Invalid(where + ": user " + f[0] + " appears twice");
            m.put(f[0], new Entry(f[0], f[1], f[2], h, grants));
        }
        int k = 0;
        for (Token tok : t.values()) {
            if (!m.containsKey(tok.user)) throw new Invalid(tokenWhere.get(k) + ": the token's user " + tok.user + " does not exist");
            k++;
        }
        return new CredentialStore(m, t);
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
        return new CredentialStore(m, new LinkedHashMap<>(tokens));
    }

    /** A copy without {@code user}, and without that user's tokens. */
    CredentialStore without(String user) {
        Map<String, Entry> m = new LinkedHashMap<>(users);
        m.remove(user);
        Map<String, Token> t = new LinkedHashMap<>(tokens);
        t.values().removeIf(x -> x.user.equals(user));
        return new CredentialStore(m, t);
    }

    /** The file form. */
    String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Cresco wsapi credentials: user:tenant:role:pbkdf2-sha384$iterations$salt$hash[:stream grants]\n");
        sb.append("# Manage with: java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool help\n");
        for (Entry e : users.values()) sb.append(e.line()).append('\n');
        if (!tokens.isEmpty()) sb.append("# API tokens: +token:id:user:sha384:created:expires(0 never):label\n");
        for (Token t : tokens.values()) sb.append(t.line()).append('\n');
        return sb.toString();
    }
}
