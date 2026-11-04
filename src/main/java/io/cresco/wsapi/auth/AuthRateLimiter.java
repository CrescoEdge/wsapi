package io.cresco.wsapi.auth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Limits failed wsapi authentications without letting the limits be turned against real users.
 *
 * <p>A <em>source</em> is a client address as {@code WsRouter.sourceOf} names it (an IPv4 address, or an IPv6 /64).
 * <ul>
 *   <li><b>Per source.</b> {@code maxPerSource} failures from a source within {@code windowMs} lock the source for
 *       {@code lockoutMs}, each further lockout doubling up to {@code maxLockoutMs}. A locked source is refused (429)
 *       before its credentials are checked, except for a user who has logged in from that source before and has not
 *       failed there since: an attacker behind a shared address does not lock out that address's known users. Cheap
 *       failures (a malformed Authorization header) count like any other, against the source only.</li>
 *   <li><b>Successes forgive only their own user.</b> A user's success at a source removes that user's failures at that
 *       source from the source's count, nobody else's: an attacker cannot reset its count against a victim by logging in
 *       with its own account in between guesses.</li>
 *   <li><b>Per user name, growing delays, never a lock.</b> A user name's failures at one source give that (user,
 *       source) pair a growing delay (after {@link #FREE_PAIR_FAILURES} free failures: 1 s, 2 s, 4 s, ... up to
 *       {@code maxLockoutMs}). Failures elsewhere never refuse the real user at a source of their own, so a stranger
 *       cannot lock a user out. When a user name has {@code maxPerUser} failures within the window from all sources
 *       (a distributed guess), each further new source gets one free failure instead of {@link #FREE_PAIR_FAILURES}.</li>
 *   <li><b>The service key</b> (one secret, an admin) also has a fabric-wide failure count: after {@code maxPerSource}
 *       x 3 failures within the window from all sources together, sources that have not used the key successfully in the
 *       last day are refused key attempts, for growing periods; sources that have keep working.</li>
 *   <li><b>Bounded memory, never fail closed.</b> Each table keeps at most {@code maxTracked} entries and forgets the
 *       least recently used one when full. A source or user that was never seen, or was forgotten, starts clean: a full
 *       table never refuses a newcomer.</li>
 * </ul>
 */
public final class AuthRateLimiter {

    /** Failures a (user, source) pair has before its delays start. */
    public static final int FREE_PAIR_FAILURES = 3;
    /** How long a successful service-key source is trusted past the fabric-wide key throttle. */
    static final long KEY_TRUST_MS = 24L * 3600_000L;
    /** The bucket name of the service key in the per-pair table (no user name contains a NUL). */
    public static final String SERVICE_KEY = "\u0000service-key";

    private final int maxPerSource;
    private final int maxPerUser;
    private final long windowMs;
    private final long lockoutMs;
    private final long maxLockoutMs;
    private final int maxTracked;
    private final LongSupplier clock;

    private static final class SourceState {
        long windowStart;
        int failures;                                   // failures counted in the current window
        final Map<String, Integer> byUser = new LinkedHashMap<>();   // their attribution (null user = cheap)
        long lockedUntil;
        int lockouts;
    }

    private static final class PairState {
        int failures;                                   // since the last success
        long lastFailure;
        long nextAllowed;
        long succeededAt;                               // 0: never
    }

    private static final class UserState {
        long windowStart;
        int failures;
    }

    private final Lru<String, SourceState> sources;
    private final Lru<String, PairState> pairs;
    private final Lru<String, UserState> users;
    private final Lru<String, Long> keyTrusted;
    private long keyWindowStart, keyLockedUntil;
    private int keyFailures, keyLockouts;

    /** A size-bounded LRU map. */
    private static final class Lru<K, V> extends LinkedHashMap<K, V> {
        private final int max;

        Lru(int max) {
            super(256, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> e) {
            return size() > max;
        }
    }

    public AuthRateLimiter(int maxPerSource, int maxPerUser, long windowMs, long lockoutMs, long maxLockoutMs,
                           int maxTracked, LongSupplier clock) {
        this.maxPerSource = Math.max(1, maxPerSource);
        this.maxPerUser = Math.max(1, maxPerUser);
        this.windowMs = Math.max(1000L, windowMs);
        this.lockoutMs = Math.max(1000L, lockoutMs);
        this.maxLockoutMs = Math.max(this.lockoutMs, maxLockoutMs);
        this.maxTracked = Math.max(16, maxTracked);
        this.clock = clock;
        this.sources = new Lru<>(this.maxTracked);
        this.pairs = new Lru<>(this.maxTracked);
        this.users = new Lru<>(this.maxTracked);
        this.keyTrusted = new Lru<>(this.maxTracked);
    }

    /** Defaults: 10 failures per source in 60 s lock it for 60 s, doubling to 15 min; 50 per user mark an attack. */
    public static AuthRateLimiter defaults() {
        return new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 100_000, System::currentTimeMillis);
    }

    private static String pairKey(String source, String user) {
        return (user == null ? "" : user) + "\u0000" + source;
    }

    /**
     * Milliseconds until an attempt from {@code source} for {@code user} (null when the request names none, e.g. a
     * malformed header) may be checked; 0 when it may be checked now.
     */
    public synchronized long retryAfterMs(String source, String user) {
        long now = clock.getAsLong();
        long wait = 0;
        PairState p = user == null ? null : pairs.get(pairKey(source, user));
        boolean knownGood = p != null && p.succeededAt > 0 && p.failures == 0;
        SourceState s = sources.get(source);
        if (s != null && s.lockedUntil > now && !knownGood) wait = s.lockedUntil - now;
        if (p != null && p.nextAllowed > now) wait = Math.max(wait, p.nextAllowed - now);
        if (SERVICE_KEY.equals(user) && keyLockedUntil > now) {
            Long trusted = keyTrusted.get(source);
            if (trusted == null || now - trusted > KEY_TRUST_MS) wait = Math.max(wait, keyLockedUntil - now);
        }
        return wait;
    }

    /** Record a failed attempt from {@code source} for {@code user} (null: no user named, a cheap failure). */
    public synchronized void failure(String source, String user) {
        long now = clock.getAsLong();
        SourceState s = sources.computeIfAbsent(source, k -> new SourceState());
        if (now - s.windowStart > windowMs) {
            s.windowStart = now;
            s.failures = 0;
            s.byUser.clear();
        }
        s.failures++;
        String who = user == null ? "" : user;
        s.byUser.merge(who, 1, Integer::sum);
        if (s.byUser.size() > 64) {                                 // bounded attribution; the total still counts
            Iterator<String> it = s.byUser.keySet().iterator();
            it.next();
            it.remove();
        }
        if (s.failures >= maxPerSource && s.lockedUntil <= now) {
            s.lockedUntil = now + Math.min(maxLockoutMs, lockoutMs << Math.min(20, s.lockouts));
            s.lockouts++;
        }
        if (user == null) return;

        UserState u = users.computeIfAbsent(user, k -> new UserState());
        if (now - u.windowStart > windowMs) {
            u.windowStart = now;
            u.failures = 0;
        }
        u.failures++;
        boolean underAttack = u.failures >= maxPerUser;

        PairState p = pairs.computeIfAbsent(pairKey(source, user), k -> new PairState());
        if (p.failures > 0 && now - p.lastFailure > maxLockoutMs) p.failures = 0;   // old failures expire
        p.failures++;
        p.lastFailure = now;
        int free = underAttack ? 1 : FREE_PAIR_FAILURES;
        if (p.failures > free) {
            long delay = Math.min(maxLockoutMs, 1000L << Math.min(20, p.failures - free - 1));
            p.nextAllowed = now + delay;
        }

        if (SERVICE_KEY.equals(user)) {
            if (now - keyWindowStart > windowMs) {
                keyWindowStart = now;
                keyFailures = 0;
            }
            keyFailures++;
            if (keyFailures >= maxPerSource * 3 && keyLockedUntil <= now) {
                keyLockedUntil = now + Math.min(maxLockoutMs, lockoutMs << Math.min(20, keyLockouts));
                keyLockouts++;
            }
        }
    }

    /**
     * Record a success: {@code user}'s failures at {@code source} are forgiven there (nobody else's), and the pair is
     * remembered as known good. A service-key success also trusts the source past the fabric-wide key throttle.
     */
    public synchronized void success(String source, String user) {
        long now = clock.getAsLong();
        String who = user == null ? "" : user;
        SourceState s = sources.get(source);
        if (s != null) {
            Integer mine = s.byUser.remove(who);
            if (mine != null) s.failures = Math.max(0, s.failures - mine);
            if (s.failures < maxPerSource) s.lockedUntil = 0;   // what is left of others' failures is under the limit
            if (s.failures == 0 && s.byUser.isEmpty()) s.lockouts = 0;
        }
        if (user == null) return;
        PairState p = pairs.computeIfAbsent(pairKey(source, user), k -> new PairState());
        p.failures = 0;
        p.nextAllowed = 0;
        p.succeededAt = now;
        if (SERVICE_KEY.equals(user)) keyTrusted.put(source, now);
    }

    synchronized int trackedSources() { return sources.size(); }
}
