package io.cresco.wsapi.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Authenticates a wsapi client from its HTTP upgrade request: per-user credentials ({@code Authorization: Basic},
 * checked against the {@link CredentialStore}) or the legacy {@code cresco_service_key} header (an {@code admin} of
 * this agent's tenant, unless disabled, and only when it is strong: {@link #serviceKeyWeakness}). Then decides whether
 * that caller may use this agent's wsapi at all: a caller of another tenant only as an {@code admin} of a superuser
 * tenant.
 *
 * <p>Failures are limited by {@link AuthRateLimiter}; a refused source or user is answered 429 before its credentials
 * are checked. An unknown user costs the same PBKDF2 work as a known one. A verified password is remembered for
 * {@code cacheMs} as an HMAC under a per-process random key (never the password), so a client opening several
 * connections does not pay PBKDF2 for each; a store change forgets every remembered password.
 *
 * <p>{@link #authenticateFast} answers everything that costs no PBKDF2 (refusals, malformed headers, the service key,
 * remembered passwords) and can run on a network thread; only an unremembered password needs {@link #authenticate}
 * on a worker. Secrets never appear in a {@link Result}.
 */
public final class Authenticator {

    /** The authenticated client. */
    public static final class Principal {
        public final String tenant;
        public final String user;
        public final String role;
        public final boolean serviceKey;
        /** Dataplane streams granted besides the user's own (exact names, or prefixes ending in *). */
        public final List<String> grants;
        /**
         * What the per-caller limits (connections, RPCs in flight) count this caller under besides tenant and user: the
         * service key's source bucket, so every tool on every host that shares the key does not share one budget; null
         * for a per-user credential.
         */
        public final String scope;

        Principal(String tenant, String user, String role, boolean serviceKey) {
            this(tenant, user, role, serviceKey, List.of(), null);
        }

        Principal(String tenant, String user, String role, boolean serviceKey, List<String> grants) {
            this(tenant, user, role, serviceKey, grants, null);
        }

        Principal(String tenant, String user, String role, boolean serviceKey, List<String> grants, String scope) {
            this.tenant = tenant;
            this.user = user;
            this.role = role;
            this.serviceKey = serviceKey;
            this.grants = grants == null ? List.of() : List.copyOf(grants);
            this.scope = scope;
        }

        public boolean isAdmin() { return CredentialStore.ROLE_ADMIN.equals(role); }

        /** {@code tenant/user}, or {@code tenant/user@scope}: one key per caller (limits, connection counts). */
        public String key() { return tenant + "/" + user + (scope == null ? "" : "@" + scope); }

        /** {@code api:<tenant>/<user>}, as the controller's caller stamp names it. */
        public String caller() { return "api:" + tenant + "/" + user; }

        /** {@code api:<tenant>/<user>[role]}. */
        @Override
        public String toString() { return caller() + "[" + role + "]"; }
    }

    /** The outcome. {@code status} is 0 on success, else the HTTP status to answer the upgrade with. */
    public static final class Result {
        public final int status;
        public final Principal principal;
        /** For the client: never says which part of a credential was wrong. */
        public final String clientMessage;
        /** For the log: why (no secrets). */
        public final String logReason;
        public final long retryAfterSeconds;
        /** The user name tried (for the log), or null. */
        public final String attemptedUser;

        private Result(int status, Principal p, String clientMessage, String logReason, long retryAfterSeconds, String attemptedUser) {
            this.status = status;
            this.principal = p;
            this.clientMessage = clientMessage;
            this.logReason = logReason;
            this.retryAfterSeconds = retryAfterSeconds;
            this.attemptedUser = attemptedUser;
        }

        public boolean ok() { return status == 0; }

        static Result ok(Principal p) { return new Result(0, p, null, null, 0, p.user); }

        static Result fail(int status, String client, String log, String user) { return new Result(status, null, client, log, 0, user); }
    }

    public static final String SERVICE_KEY_HEADER = "cresco_service_key";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    /** The weakest service key accepted (estimated bits; see {@link #serviceKeyWeakness}). */
    public static final int MIN_SERVICE_KEY_BITS = 128;
    private static final Set<String> WEAK_KEYS = Set.of("12345", "123456", "1234567890", "password", "changeit", "cresco",
            "secret", "admin", "servicekey", "service-key", "service_key", "test", "default", "letmein", "qwerty", "mykey",
            "your-service-key", "a-service-key");

    private final Supplier<CredentialStore> store;
    private final Supplier<String> serviceKey;
    private final BooleanSupplier serviceKeyEnabled;
    private final Supplier<String> agentTenant;
    private final Supplier<Set<String>> superuserTenants;
    private final AuthRateLimiter limiter;
    private final long cacheMs;
    private final LongSupplier clock;

    private final byte[] cacheKey = new byte[48];
    private final Map<String, Remembered> verified = new ConcurrentHashMap<>();
    private static final int MAX_REMEMBERED = 4096;
    private final PasswordHash decoy;
    private volatile String checkedKey;
    private volatile String checkedKeyWeakness;

    private static final class Remembered {
        final long expires;
        final CredentialStore store;

        Remembered(long expires, CredentialStore store) {
            this.expires = expires;
            this.store = store;
        }
    }

    public Authenticator(Supplier<CredentialStore> store, Supplier<String> serviceKey, BooleanSupplier serviceKeyEnabled,
                         Supplier<String> agentTenant, Supplier<Set<String>> superuserTenants, AuthRateLimiter limiter,
                         long cacheMs, LongSupplier clock) {
        this.store = store;
        this.serviceKey = serviceKey;
        this.serviceKeyEnabled = serviceKeyEnabled;
        this.agentTenant = agentTenant;
        this.superuserTenants = superuserTenants == null ? Collections::emptySet : superuserTenants;
        this.limiter = limiter;
        this.cacheMs = Math.max(0L, cacheMs);
        this.clock = clock;
        SecureRandom r = new SecureRandom();
        r.nextBytes(cacheKey);
        char[] junk = new char[24];
        for (int i = 0; i < junk.length; i++) junk[i] = (char) ('a' + r.nextInt(26));
        this.decoy = PasswordHash.create(junk, r);
    }

    /**
     * Why a service key is too weak to enable, or null when it is strong enough: a known weak value, fewer than 8
     * distinct characters, or fewer than {@link #MIN_SERVICE_KEY_BITS} bits estimated as its length times log2 of the
     * size of the character classes it uses (lower case, upper case, digits, others). 32 random hex digits (128 bits)
     * pass; {@code 12345} does not.
     */
    public static String serviceKeyWeakness(String key) {
        if (key == null || key.isEmpty()) return "it is empty";
        if (WEAK_KEYS.contains(key.toLowerCase(Locale.ROOT))) return "it is a well-known weak value";
        if (key.chars().distinct().count() < 8) return "it has fewer than 8 distinct characters";
        int classes = 0;
        if (key.chars().anyMatch(c -> c >= 'a' && c <= 'z')) classes += 26;
        if (key.chars().anyMatch(c -> c >= 'A' && c <= 'Z')) classes += 26;
        if (key.chars().anyMatch(c -> c >= '0' && c <= '9')) classes += 10;
        if (key.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes += 33;
        double bits = key.length() * (Math.log(Math.max(2, classes)) / Math.log(2));
        if (bits < MIN_SERVICE_KEY_BITS) {
            return "it has about " + (int) bits + " bits; at least " + MIN_SERVICE_KEY_BITS + " are required (e.g. 32 random hex digits)";
        }
        return null;
    }

    /** Why the configured service key is not in use, or null when it is. */
    public String serviceKeyProblem() {
        if (!serviceKeyEnabled.getAsBoolean()) return "service key authentication is disabled (wsapi_service_key_enabled=false)";
        String k = serviceKey.get();
        if (k == null || k.isEmpty()) return "no cresco_service_key is configured";
        String w = weakness(k);
        return w == null ? null : "the configured cresco_service_key is too weak and is not accepted: " + w;
    }

    private String weakness(String key) {
        if (!key.equals(checkedKey)) {
            checkedKeyWeakness = serviceKeyWeakness(key);
            checkedKey = key;
        }
        return checkedKeyWeakness;
    }

    /**
     * Authenticate one upgrade request.
     *
     * @param authorization the {@code Authorization} header, or null
     * @param serviceKeyHeader the {@code cresco_service_key} header, or null
     * @param source the client source (rate limiting and logs; see {@code WsRouter.sourceOf})
     */
    public Result authenticate(String authorization, String serviceKeyHeader, String source) {
        Result fast = authenticateFast(authorization, serviceKeyHeader, source);
        if (fast != null) return fast;
        return basicSlow(parseBasic(authorization), source, agentTenant.get());
    }

    /**
     * Everything that needs no PBKDF2: an unknown tenant, a refused source or user, missing or malformed credentials, the
     * service key, and a remembered password. Null when only a password check ({@link #authenticate}) can decide.
     */
    public Result authenticateFast(String authorization, String serviceKeyHeader, String source) {
        String tenant = agentTenant.get();
        if (tenant == null || tenant.isEmpty()) {
            return Result.fail(503, "wsapi is not ready: this agent's tenant is not known yet", "agent tenant unknown", null);
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return token(authorization.substring(7).trim(), source, tenant);
        }
        if (authorization != null) {
            String[] up = parseBasic(authorization);
            String user = up == null ? null : up[0];
            long wait = limiter.retryAfterMs(source, user);
            if (wait > 0) return limited(wait, user);
            if (up == null) {
                limiter.failure(source, null);
                return Result.fail(401, "authentication failed", "malformed Authorization header (expected Basic user:password)", null);
            }
            CredentialStore s = store.get();
            CredentialStore.Entry e = s.get(user);
            if (e != null && rememberedOk(s, user, up[1])) return accept(e, source, tenant);
            return null;                                         // a PBKDF2 check decides
        }
        if (serviceKeyHeader != null) return serviceKey(serviceKeyHeader, source, tenant);
        limiter.failure(source, null);
        return Result.fail(401, "authentication required", "no credentials", null);
    }

    /**
     * An API token ({@code Authorization: Bearer cwt1_<id>.<secret>}): checked on the network thread (one SHA-384, no
     * PBKDF2), limited per source only, never per user: 256 bits cannot be guessed, so a tool that uses a token is never
     * delayed by strangers guessing its user's password.
     */
    private Result token(String presented, String source, String tenant) {
        long wait = limiter.retryAfterMs(source, null);
        if (wait > 0) return limited(wait, null);
        java.util.regex.Matcher m = CredentialStore.Token.FORM.matcher(presented);
        if (!m.matches()) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "malformed API token", null);
        }
        CredentialStore s = store.get();
        CredentialStore.Token t = s.token(m.group(1));
        if (t == null || !t.matches(presented)) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", t == null ? "unknown API token" : "wrong API token " + t.id,
                    t == null ? null : t.user);
        }
        if (t.expired(clock.getAsLong())) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "API token " + t.id + " has expired", t.user);
        }
        CredentialStore.Entry e = s.get(t.user);
        if (e == null) return Result.fail(401, "authentication failed", "API token " + t.id + ": its user no longer exists", t.user);
        return accept(e, source, tenant);
    }

    private Result serviceKey(String provided, String source, String tenant) {
        long wait = limiter.retryAfterMs(source, AuthRateLimiter.SERVICE_KEY);
        if (wait > 0) return limited(wait, CredentialStore.SERVICE_KEY_USER);
        String problem = serviceKeyProblem();
        if (problem != null) {
            limiter.failure(source, AuthRateLimiter.SERVICE_KEY);
            return Result.fail(401, "authentication failed", problem, CredentialStore.SERVICE_KEY_USER);
        }
        if (!keyMatches(serviceKey.get(), provided)) {
            limiter.failure(source, AuthRateLimiter.SERVICE_KEY);
            return Result.fail(401, "authentication failed", "wrong service key", CredentialStore.SERVICE_KEY_USER);
        }
        limiter.success(source, AuthRateLimiter.SERVICE_KEY);
        return Result.ok(new Principal(tenant, CredentialStore.SERVICE_KEY_USER, CredentialStore.ROLE_ADMIN, true, List.of(),
                serviceKeyLimitsPerSource ? AuthRateLimiter.bucket(source) : null));
    }

    private volatile boolean serviceKeyLimitsPerSource = true;

    /**
     * Count the service key's connections and RPCs in flight per source host ({@code wsapi_service_key_limits_per_source},
     * on by default): every client of the key used to share one budget, so one busy tool could starve the others.
     */
    public Authenticator serviceKeyLimitsPerSource(boolean perSource) {
        this.serviceKeyLimitsPerSource = perSource;
        return this;
    }

    /** A WARNING to log when wrong service keys arrive at a guessing rate ({@link AuthRateLimiter#serviceKeyAlarm}), else null. */
    public String serviceKeyAlarm() {
        return limiter.serviceKeyAlarm();
    }

    private Result basicSlow(String[] up, String source, String agentTenantName) {
        if (agentTenantName == null || agentTenantName.isEmpty()) {
            return Result.fail(503, "wsapi is not ready: this agent's tenant is not known yet", "agent tenant unknown", null);
        }
        if (up == null) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "malformed Authorization header (expected Basic user:password)", null);
        }
        String user = up[0];
        long wait = limiter.retryAfterMs(source, user);          // again: other attempts may have failed meanwhile
        if (wait > 0) return limited(wait, user);
        char[] secret = up[1].toCharArray();
        try {
            CredentialStore s = store.get();
            CredentialStore.Entry e = s.get(user);
            boolean good;
            if (e == null) {
                decoy.matches(secret);                       // same work as a real check: no user enumeration by timing
                good = false;
            } else {
                good = e.matches(secret);
            }
            if (!good) {
                limiter.failure(source, user);
                return Result.fail(401, "authentication failed", e == null ? "unknown user" : "wrong password", user);
            }
            String weak = PasswordPolicy.weakness(user, secret);
            if (weak != null) {
                // the right password, but one the policy refuses (set before it, or written by hand): not usable until changed
                return Result.fail(401, "authentication failed", "the password of " + user + " does not meet the password policy ("
                        + weak + "); set a new one with CredentialTool passwd", user);
            }
            remember(s, user, up[1]);
            return accept(e, source, agentTenantName);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private Result accept(CredentialStore.Entry e, String source, String agentTenantName) {
        limiter.success(source, e.user);
        Principal p = new Principal(e.tenant, e.user, e.role, false, e.grants);
        if (!p.tenant.equals(agentTenantName)) {
            boolean superAdmin = p.isAdmin() && superuserTenants.get().contains(p.tenant);
            if (!superAdmin) {
                return Result.fail(403, "forbidden: a caller of tenant " + p.tenant + " cannot use the wsapi of an agent in tenant "
                                + agentTenantName + " (only an admin of a superuser tenant can)",
                        p + " is not of this agent's tenant " + agentTenantName, e.user);
            }
        }
        return Result.ok(p);
    }

    private Result limited(long waitMs, String user) {
        long secs = Math.max(1L, (waitMs + 999L) / 1000L);
        return new Result(429, null, "too many failed attempts; retry in " + secs + " s",
                "rate limited (" + secs + " s left)", secs, user);
    }

    /** {@code [user, password]} from {@code Basic base64(user:password)}, or null when malformed. */
    static String[] parseBasic(String header) {
        if (header == null) return null;
        String h = header.strip();
        if (h.length() < 6 || !h.substring(0, 6).toLowerCase(Locale.ROOT).equals("basic ")) return null;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(h.substring(6).strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String s = new String(raw, StandardCharsets.UTF_8);
        Arrays.fill(raw, (byte) 0);
        int c = s.indexOf(':');
        if (c <= 0 || c == s.length() - 1) return null;
        String user = s.substring(0, c);
        if (!CredentialStore.USER.matcher(user).matches()) return null;
        return new String[]{user, s.substring(c + 1)};
    }

    /** Constant-time comparison: String.equals returns at the first differing character. */
    public static boolean keyMatches(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private String rememberKey(String user, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA384");
            mac.init(new SecretKeySpec(cacheKey, "HmacSHA384"));
            mac.update(user.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] pw = secret.getBytes(StandardCharsets.UTF_8);
            try {
                return Base64.getEncoder().encodeToString(mac.doFinal(pw));
            } finally {
                Arrays.fill(pw, (byte) 0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA384 unavailable", e);
        }
    }

    private boolean rememberedOk(CredentialStore s, String user, String secret) {
        if (cacheMs == 0) return false;
        Remembered r = verified.get(rememberKey(user, secret));
        return r != null && r.store == s && r.expires > clock.getAsLong();
    }

    private void remember(CredentialStore s, String user, String secret) {
        if (cacheMs == 0) return;
        long now = clock.getAsLong();
        if (verified.size() >= MAX_REMEMBERED) {
            for (Iterator<Map.Entry<String, Remembered>> it = verified.entrySet().iterator(); it.hasNext(); ) {
                Remembered r = it.next().getValue();
                if (r.expires <= now || r.store != s) it.remove();
            }
            if (verified.size() >= MAX_REMEMBERED) verified.clear();
        }
        verified.put(rememberKey(user, secret), new Remembered(now + cacheMs, s));
    }

    /**
     * May {@code p} address messages to tenant {@code tenant}? Its own tenant always; another tenant only as an
     * {@code admin} of a superuser tenant ({@code broker_superuser_tenants}).
     */
    public boolean mayTarget(Principal p, String tenant) {
        if (p == null || tenant == null) return false;
        if (p.tenant.equals(tenant)) return true;
        return p.isAdmin() && superuserTenants.get().contains(p.tenant);
    }

    /** This agent's tenant, or null while it is not known. */
    public String agentTenant() { return agentTenant.get(); }

    /** Forget every remembered password (the store changed). */
    public void forgetVerified() { verified.clear(); }
}
