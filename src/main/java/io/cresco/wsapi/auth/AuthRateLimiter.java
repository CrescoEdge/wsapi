package io.cresco.wsapi.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Limits failed wsapi authentications without letting the limits be turned against real users.
 *
 * <p>A <em>source</em> is what {@code WsRouter.sourceOf} names: {@code "<bucket> <address>"}, where the bucket is an IPv4
 * address or an IPv6 prefix (a /64 by default; an IPv4 address embedded by NAT64 counts as that IPv4 address) and the
 * address is the exact client address. A plain string (a test, an unknown transport) is both. Failures are counted per
 * bucket; who a user is known as is remembered per exact address and per bucket.
 * <ul>
 *   <li><b>Known-good sources.</b> A user's successful login remembers that user's exact address and its bucket for
 *       {@link #knownGoodTtlMs} (30 days by default), as keyed hashes (HMAC-SHA-256 under a per-installation secret, so
 *       the store names no one), persisted by {@code KnownGoodFile} so a restart keeps them. An exact known-good address
 *       is never delayed by anyone else's failures: not by its bucket's lockout, not by the user's budgets. Only its own
 *       failures there delay it.</li>
 *   <li><b>Per bucket.</b> {@code maxPerSource} failures from a bucket within {@code windowMs} lock it for
 *       {@code lockoutMs}, each further lockout doubling up to {@code maxLockoutMs} (exact known-good addresses excepted).
 *       A user's success removes that user's own failures from the bucket's count, nobody else's. Malformed headers count
 *       here only (cheap).</li>
 *   <li><b>Per (user, address)</b>: after {@link #FREE_PAIR_FAILURES} failures, growing delays (1 s, 2 s, 4 s ... up to
 *       {@code maxLockoutMs}) for that address only, never a lock elsewhere.</li>
 *   <li><b>A user's unknown sources share a bounded budget.</b> Failures for a user from sources not known for that user
 *       count against {@code maxPerUser} per window; once it is spent, attempts for that user from unknown sources are
 *       refused until the window ends, so a guesser with many addresses gets at most {@code maxPerUser} password checks
 *       per window. New addresses in a bucket the user is known in (another device on the same LAN, an IPv6 privacy
 *       address) share a smaller budget per (user, bucket), {@code maxPerSource}, so a stranger on that LAN cannot rotate
 *       addresses without bound. Known hosts are never refused by either budget.</li>
 *   <li><b>The service key</b> (one secret of at least 128 bits, weak keys being refused) is limited per bucket and per
 *       address like anything else, but never by a fabric-wide gate: wrong keys from strangers must not lock the real
 *       key out of a new host. Their total is a metric ({@link #SERVICE_KEY_FAILURES}) and an alarm
 *       ({@link #serviceKeyAlarm}) instead.</li>
 *   <li><b>Bounded memory, never fail closed.</b> Each table keeps at most {@code maxTracked} entries and forgets the
 *       least recently used one; a source or user that was never seen, or was forgotten, starts clean.</li>
 * </ul>
 */
public final class AuthRateLimiter {

    /** Failures a (user, address) pair has before its delays start. */
    public static final int FREE_PAIR_FAILURES = 3;
    /** The user name the service key is limited under (no user name contains a NUL). */
    public static final String SERVICE_KEY = "\u0000service-key";
    /** How long a successful login keeps a user's address and bucket known-good, by default. */
    public static final long DEFAULT_KNOWN_GOOD_TTL_MS = 30L * 24 * 3600_000L;

    /** Process-wide totals for the metric inventory: failed authentications, wrong service keys, refusals (429). */
    public static final AtomicLong FAILURES = new AtomicLong(), SERVICE_KEY_FAILURES = new AtomicLong(), REFUSED = new AtomicLong();

    private final int maxPerSource;
    private final int maxPerUser;
    private final long windowMs;
    private final long lockoutMs;
    private final long maxLockoutMs;
    private final int maxTracked;
    private final LongSupplier clock;
    private long knownGoodTtlMs = DEFAULT_KNOWN_GOOD_TTL_MS;

    private static final class SourceState {
        long windowStart;
        int failures;                                   // failures counted in the current window
        final Map<String, Integer> byUser = new LinkedHashMap<>();   // their attribution (empty name = no user named)
        long lockedUntil;
        int lockouts;
    }

    private static final class PairState {
        int failures;                                   // since the last success
        long lastFailure;
        long nextAllowed;
    }

    private static final class Budget {
        long windowStart;
        int failures;
    }

    private final Lru<String, SourceState> sources;
    private final Lru<String, PairState> pairs;
    private final Lru<String, Budget> unknownBudgets;       // per user
    private final Lru<String, Budget> bucketBudgets;        // per (user, known bucket)
    private KnownGood known;

    private long keyWindowStart, lastKeyAlarm;
    private int keyFailuresInWindow;
    private final Set<String> keySourcesInWindow = new HashSet<>();

    /** A size-bounded LRU map. */
    static final class Lru<K, V> extends LinkedHashMap<K, V> {
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

    /** Who is known good where: HMAC-SHA-256 ids of (user, address or bucket), each with its expiry. */
    static final class KnownGood {
        private final Mac mac;                          // used under the limiter's lock only
        final Lru<String, Long> entries;
        boolean dirty;

        KnownGood(byte[] key, int max) {
            try {
                this.mac = Mac.getInstance("HmacSHA256");
                this.mac.init(new SecretKeySpec(key.clone(), "HmacSHA256"));
            } catch (Exception e) {
                throw new IllegalStateException("HmacSHA256 unavailable", e);
            }
            this.entries = new Lru<>(max);
        }

        String id(String user, String where) {
            try {
                byte[] h = mac.doFinal((user + "\u0000" + where).getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(32);
                for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i] & 0xff));
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException("HmacSHA256 unavailable", e);
            }
        }

        boolean contains(String user, String where, long now) {
            Long exp = entries.get(id(user, where));
            return exp != null && exp > now;
        }

        void add(String user, String where, long now, long ttl) {
            String id = id(user, where);
            Long old = entries.get(id);
            if (old == null || old < now + ttl / 2) {           // refresh at most every half-life: few writes
                entries.put(id, now + ttl);
                dirty = true;
            }
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
        this.unknownBudgets = new Lru<>(this.maxTracked);
        this.bucketBudgets = new Lru<>(this.maxTracked);
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        this.known = new KnownGood(k, this.maxTracked);
    }

    /** Defaults: 10 failures per bucket in 60 s lock it for 60 s, doubling to 15 min; 50 per user from unknown sources. */
    public static AuthRateLimiter defaults() {
        return new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 100_000, System::currentTimeMillis);
    }

    /** The bucket of a source ({@code "<bucket> <address>"}, or a plain string; a bare IPv6 address is aggregated). */
    public static String bucket(String source) {
        return Sources.bucket(Sources.normalize(source));
    }

    /** The exact address of a source. */
    public static String address(String source) {
        return Sources.address(Sources.normalize(source));
    }

    private static String pairKey(String address, String user) {
        return (user == null ? "" : user) + "\u0000" + address;
    }

    private boolean live(Budget b, long now) {
        return b != null && now - b.windowStart <= windowMs;
    }

    /**
     * Milliseconds until an attempt from {@code source} for {@code user} (null when the request names none, e.g. a
     * malformed header) may be checked; 0 when it may be checked now.
     */
    public synchronized long retryAfterMs(String source, String user) {
        long now = clock.getAsLong();
        String src = Sources.normalize(source), b = Sources.bucket(src), a = Sources.address(src);
        boolean exactKnown = user != null && known.contains(user, a, now);
        long wait = 0;
        SourceState s = sources.get(b);
        if (s != null && s.lockedUntil > now && !exactKnown) wait = s.lockedUntil - now;
        if (user != null) {
            PairState p = pairs.get(pairKey(a, user));
            if (p != null && p.nextAllowed > now) wait = Math.max(wait, p.nextAllowed - now);
            if (!exactKnown && !SERVICE_KEY.equals(user)) {
                Budget budget = known.contains(user, b, now) ? bucketBudgets.get(pairKey(b, user)) : unknownBudgets.get(user);
                int max = known.contains(user, b, now) ? maxPerSource : maxPerUser;
                if (live(budget, now) && budget.failures >= max) wait = Math.max(wait, budget.windowStart + windowMs - now + 1);
            }
        }
        if (wait > 0) REFUSED.incrementAndGet();
        return wait;
    }

    /** Record a failed attempt from {@code source} for {@code user} (null: no user named, a cheap failure). */
    public synchronized void failure(String source, String user) {
        long now = clock.getAsLong();
        FAILURES.incrementAndGet();
        String src = Sources.normalize(source), b = Sources.bucket(src), a = Sources.address(src);
        SourceState s = sources.computeIfAbsent(b, k -> new SourceState());
        if (now - s.windowStart > windowMs) {
            s.windowStart = now;
            s.failures = 0;
            s.byUser.clear();
        }
        s.failures++;
        s.byUser.merge(user == null ? "" : user, 1, Integer::sum);
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

        PairState p = pairs.computeIfAbsent(pairKey(a, user), k -> new PairState());
        if (p.failures > 0 && now - p.lastFailure > maxLockoutMs) p.failures = 0;   // old failures expire
        p.failures++;
        p.lastFailure = now;
        if (p.failures > FREE_PAIR_FAILURES) {
            p.nextAllowed = now + Math.min(maxLockoutMs, 1000L << Math.min(20, p.failures - FREE_PAIR_FAILURES - 1));
        }

        if (SERVICE_KEY.equals(user)) {
            SERVICE_KEY_FAILURES.incrementAndGet();
            if (now - keyWindowStart > windowMs) {
                keyWindowStart = now;
                keyFailuresInWindow = 0;
                keySourcesInWindow.clear();
            }
            keyFailuresInWindow++;
            if (keySourcesInWindow.size() < 10_000) keySourcesInWindow.add(b);
            return;
        }
        if (known.contains(user, a, now)) return;                   // its own pair delays only
        Budget budget = known.contains(user, b, now)
                ? bucketBudgets.computeIfAbsent(pairKey(b, user), k -> new Budget())
                : unknownBudgets.computeIfAbsent(user, k -> new Budget());
        if (!live(budget, now)) {
            budget.windowStart = now;
            budget.failures = 0;
        }
        budget.failures++;
    }

    /**
     * Record a success: {@code user}'s failures at {@code source} are forgiven there (nobody else's), and the user's
     * address and bucket are remembered as known good.
     */
    public synchronized void success(String source, String user) {
        long now = clock.getAsLong();
        String src = Sources.normalize(source), b = Sources.bucket(src), a = Sources.address(src);
        String who = user == null ? "" : user;
        SourceState s = sources.get(b);
        if (s != null) {
            Integer mine = s.byUser.remove(who);
            if (mine != null) s.failures = Math.max(0, s.failures - mine);
            if (s.failures < maxPerSource) s.lockedUntil = 0;   // what is left of others' failures is under the limit
            if (s.failures == 0 && s.byUser.isEmpty()) s.lockouts = 0;
        }
        if (user == null) return;
        PairState p = pairs.get(pairKey(a, user));
        if (p != null) {
            p.failures = 0;
            p.nextAllowed = 0;
        }
        known.add(user, a, now, knownGoodTtlMs);
        if (!b.equals(a)) known.add(user, b, now, knownGoodTtlMs);
    }

    /**
     * A WARNING to log when wrong service keys arrive at a rate that means someone is guessing ({@code maxPerSource} x 3
     * within the window, from anywhere), at most once per window; else null. The key itself is never throttled for it.
     */
    public synchronized String serviceKeyAlarm() {
        long now = clock.getAsLong();
        if (now - keyWindowStart > windowMs || keyFailuresInWindow < maxPerSource * 3) return null;
        if (now - lastKeyAlarm < windowMs) return null;
        lastKeyAlarm = now;
        return keyFailuresInWindow + " wrong service keys within " + (windowMs / 1000) + " s from " + keySourcesInWindow.size()
                + " source(s): someone is guessing it (" + SERVICE_KEY_FAILURES.get() + " since start); each source is "
                + "limited, the key itself is not (it has at least 128 bits)";
    }

    /** How long a successful login keeps a source known good. */
    public synchronized void knownGoodTtlMs(long ttl) {
        this.knownGoodTtlMs = Math.max(60_000L, ttl);
    }

    /** Replace the known-good store (from {@code KnownGoodFile}): its HMAC key and entries (id to expiry). */
    public synchronized void useKnownGood(byte[] hmacKey, Map<String, Long> entries) {
        KnownGood k = new KnownGood(hmacKey, maxTracked);
        long now = clock.getAsLong();
        for (Map.Entry<String, Long> e : entries.entrySet()) if (e.getValue() > now) k.entries.put(e.getKey(), e.getValue());
        this.known = k;
    }

    /** The known-good entries when they changed since the last call (unexpired ones only), else null. */
    public synchronized Map<String, Long> knownGoodIfChanged() {
        if (!known.dirty) return null;
        known.dirty = false;
        long now = clock.getAsLong();
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : known.entries.entrySet()) if (e.getValue() > now) out.put(e.getKey(), e.getValue());
        return out;
    }

    synchronized int trackedSources() { return sources.size(); }
}
