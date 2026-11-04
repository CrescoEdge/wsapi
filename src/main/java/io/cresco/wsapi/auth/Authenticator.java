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
 * this agent's tenant, unless disabled). Then decides whether that caller may use this agent's wsapi at all: a caller
 * of another tenant only as an {@code admin} of a superuser tenant.
 *
 * <p>Failures are counted ({@link AuthRateLimiter}); a locked-out source or user is refused before its credentials are
 * checked. An unknown user costs the same PBKDF2 work as a known one. A verified password is remembered for
 * {@code cacheMs} as an HMAC under a per-process random key (never the password), so a client opening several
 * connections does not pay PBKDF2 for each; a store change forgets every remembered password. Secrets never appear in
 * a {@link Result}.
 */
public final class Authenticator {

    /** The authenticated client. */
    public static final class Principal {
        public final String tenant;
        public final String user;
        public final String role;
        public final boolean serviceKey;

        Principal(String tenant, String user, String role, boolean serviceKey) {
            this.tenant = tenant;
            this.user = user;
            this.role = role;
            this.serviceKey = serviceKey;
        }

        public boolean isAdmin() { return CredentialStore.ROLE_ADMIN.equals(role); }

        /** {@code api:<tenant>/<user>}, as the controller's caller stamp names it. */
        @Override
        public String toString() { return "api:" + tenant + "/" + user + "[" + role + "]"; }
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
     * Authenticate one upgrade request.
     *
     * @param authorization the {@code Authorization} header, or null
     * @param serviceKeyHeader the {@code cresco_service_key} header, or null
     * @param source the client address (rate limiting and logs)
     */
    public Result authenticate(String authorization, String serviceKeyHeader, String source) {
        String tenant = agentTenant.get();
        if (tenant == null || tenant.isEmpty()) {
            return Result.fail(503, "wsapi is not ready: this agent's tenant is not known yet", "agent tenant unknown", null);
        }
        if (authorization != null) {
            return basic(authorization, source, tenant);
        }
        if (serviceKeyHeader != null) {
            return serviceKey(serviceKeyHeader, source, tenant);
        }
        return Result.fail(401, "authentication required", "no credentials", null);
    }

    private Result serviceKey(String provided, String source, String tenant) {
        long wait = limiter.retryAfterMs(source, null);
        if (wait > 0) return limited(wait, CredentialStore.SERVICE_KEY_USER);
        if (!serviceKeyEnabled.getAsBoolean()) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "service key authentication is disabled (wsapi_service_key_enabled=false)", CredentialStore.SERVICE_KEY_USER);
        }
        String expected = serviceKey.get();
        if (expected == null || expected.isEmpty()) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "no cresco_service_key is configured", CredentialStore.SERVICE_KEY_USER);
        }
        if (!keyMatches(expected, provided)) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "wrong service key", CredentialStore.SERVICE_KEY_USER);
        }
        limiter.success(source);
        return Result.ok(new Principal(tenant, CredentialStore.SERVICE_KEY_USER, CredentialStore.ROLE_ADMIN, true));
    }

    private Result basic(String header, String source, String agentTenantName) {
        String[] up = parseBasic(header);
        String user = up == null ? null : up[0];
        long wait = limiter.retryAfterMs(source, user);
        if (wait > 0) return limited(wait, user);
        if (up == null) {
            limiter.failure(source, null);
            return Result.fail(401, "authentication failed", "malformed Authorization header (expected Basic user:password)", null);
        }
        char[] secret = up[1].toCharArray();
        try {
            CredentialStore s = store.get();
            CredentialStore.Entry e = s.get(user);
            boolean good;
            if (e == null) {
                decoy.matches(secret);                       // same work as a real check: no user enumeration by timing
                good = false;
            } else {
                good = rememberedOk(s, user, secret) || e.matches(secret);
            }
            if (!good) {
                limiter.failure(source, user);
                return Result.fail(401, "authentication failed", e == null ? "unknown user" : "wrong password", user);
            }
            remember(s, user, secret);
            limiter.success(source);
            Principal p = new Principal(e.tenant, e.user, e.role, false);
            if (!p.tenant.equals(agentTenantName)) {
                boolean superAdmin = p.isAdmin() && superuserTenants.get().contains(p.tenant);
                if (!superAdmin) {
                    return Result.fail(403, "forbidden: a caller of tenant " + p.tenant + " cannot use the wsapi of an agent in tenant "
                                    + agentTenantName + " (only an admin of a superuser tenant can)",
                            p + " is not of this agent's tenant " + agentTenantName, user);
                }
            }
            return Result.ok(p);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private Result limited(long waitMs, String user) {
        long secs = Math.max(1L, (waitMs + 999L) / 1000L);
        return new Result(429, null, "too many failed attempts; retry in " + secs + " s",
                "rate limited (" + secs + " s left)", secs, user);
    }

    /** {@code [user, password]} from {@code Basic base64(user:password)}, or null when malformed. */
    static String[] parseBasic(String header) {
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

    private String rememberKey(String user, char[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA384");
            mac.init(new SecretKeySpec(cacheKey, "HmacSHA384"));
            mac.update(user.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(secret));
            byte[] pw = new byte[bb.remaining()];
            bb.get(pw);
            if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
            try {
                return Base64.getEncoder().encodeToString(mac.doFinal(pw));
            } finally {
                Arrays.fill(pw, (byte) 0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA384 unavailable", e);
        }
    }

    private boolean rememberedOk(CredentialStore s, String user, char[] secret) {
        if (cacheMs == 0) return false;
        Remembered r = verified.get(rememberKey(user, secret));
        return r != null && r.store == s && r.expires > clock.getAsLong();
    }

    private void remember(CredentialStore s, String user, char[] secret) {
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
