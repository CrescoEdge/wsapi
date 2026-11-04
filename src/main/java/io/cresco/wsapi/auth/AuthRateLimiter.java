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
 * Limits failed wsapi password checks without letting the limits be turned against real users.
 *
 * <p>A <em>source</em> is what {@code WsRouter.sourceOf} names: {@code "<bucket> <address>"}, where the bucket is an IPv4
 * address or an IPv6 prefix and the address is the exact client address ({@link Sources}).
 * <ul>
 *   <li><b>Known-good sources, per user.</b> A successful login remembers the user's exact address and bucket for
 *       {@link #knownGoodTtlMs} (30 days), as keyed hashes, persisted by {@link KnownGoodFile}. Each user's set is bounded
 *       ({@link #KNOWN_PER_USER}) and evicts only its own oldest entries, so no user can push another's out. An exact
 *       known-good address is never delayed by anyone else's failures.</li>
 *   <li><b>Per bucket.</b> {@code maxPerSource} failures within the window lock the bucket, doubling to
 *       {@code maxLockoutMs} (exact known-good addresses excepted). A user's success forgives only its own failures.</li>
 *   <li><b>Per (user, address)</b>: growing delays after {@link #FREE_PAIR_FAILURES} free failures.</li>
 *   <li><b>A user's unknown sources: fair shares, then delays, never a lockout.</b> A <em>share source</em> is an IPv4
 *       /24 ({@link Sources#share}) or the IPv6 bucket. In each window a share source may have a password checked for a
 *       user while its own failures plus one are at most {@code maxPerUser} divided by the number of share sources
 *       failing for that user (max-min fair: one stranger's sources cannot use another source's share), and while all
 *       of them together have had fewer than 2 x {@code maxPerUser} checks. Beyond that the source is delayed, not
 *       refused: its next check comes after {@link #DEFAULT_DELAYED_CHECK_MS} (30 s), doubling with each failed delayed
 *       check up to {@code maxLockoutMs}, and a success resets it. So strangers can slow a user's first login from a new
 *       place to one check per 30 s at worst, never stop it; and one user's password gets at most 2 x {@code maxPerUser}
 *       fair-share checks per window (144,000 a day by default) plus about 100 delayed checks a day per distinct share
 *       source a guesser uses.</li>
 *   <li><b>The service key</b> and API tokens (too long to guess) are limited per bucket and per address only, never
 *       per user; wrong service keys are a metric and an alarm ({@link #serviceKeyAlarm}).</li>
 *   <li><b>Bounded memory, never fail closed.</b> Every table is bounded; a forgotten source or user starts clean.</li>
 * </ul>
 */
public final class AuthRateLimiter {

    /** Failures a (user, address) pair has before its delays start. */
    public static final int FREE_PAIR_FAILURES = 3;
    /** The user name the service key is limited under (no user name contains a NUL). */
    public static final String SERVICE_KEY = "\u0000service-key";
    /** How long a successful login keeps a user's address and bucket known good, by default. */
    public static final long DEFAULT_KNOWN_GOOD_TTL_MS = 30L * 24 * 3600_000L;
    /** Known-good entries kept per user (exact addresses and buckets); a user's oldest go first. */
    public static final int KNOWN_PER_USER = 64;
    /** The first delay of a share source beyond its fair share, by default. */
    public static final long DEFAULT_DELAYED_CHECK_MS = 30_000L;
    /** Share sources tracked per user per window. */
    static final int SHARES_PER_USER = 4096;

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
    private long delayedCheckMs = DEFAULT_DELAYED_CHECK_MS;

    private static final class SourceState {
        long windowStart;
        int failures;
        final Map<String, Integer> byUser = new LinkedHashMap<>();
        long lockedUntil;
        int lockouts;
    }

    private static final class PairState {
        int failures;
        long lastFailure;
        long nextAllowed;
    }

    /** A user's unknown-source shares in the current window. */
    private static final class Shares {
        long windowStart;
        int checks;                                      // all fair-share checks this window
        final Lru<String, Integer> usage = new Lru<>(SHARES_PER_USER);
    }

    /** A share source beyond its share: when it may next be checked, and how long it waits after that. */
    private static final class Delayed {
        long nextCheck;
        long delayMs;
    }

    private final Lru<String, SourceState> sources;
    private final Lru<String, PairState> pairs;
    private final Lru<String, Shares> shares;              // per user
    private final Lru<String, Delayed> delayed;            // per (user, share source)
    private KnownGood known;

    private long keyWindowStart, lastKeyAlarm;
    private int keyFailuresInWindow;
    private final Set<String> keySourcesInWindow = new HashSet<>();

    /** A size-bounded LRU map. */
    static final class Lru<K, V> extends LinkedHashMap<K, V> {
        private final int max;

        Lru(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> e) {
            return size() > max;
        }
    }

    /**
     * Who is known good where: per user (a keyed hash of the user name), a bounded LRU of keyed hashes of (user, address or
     * bucket) with their expiry. One user's entries never evict another's.
     */
    static final class KnownGood {
        private final Mac mac;                          // used under the limiter's lock only
        final Lru<String, Lru<String, Long>> users;
        boolean dirty;

        KnownGood(byte[] key, int maxUsers) {
            try {
                this.mac = Mac.getInstance("HmacSHA256");
                this.mac.init(new SecretKeySpec(key.clone(), "HmacSHA256"));
            } catch (Exception e) {
                throw new IllegalStateException("HmacSHA256 unavailable", e);
            }
            this.users = new Lru<>(maxUsers);
        }

        String id(String user, String where) {
            byte[] h = mac.doFinal((user + "\u0000" + where).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i] & 0xff));
            return sb.toString();
        }

        String userId(String user) { return id(user, "\u0000user"); }

        boolean contains(String user, String where, long now) {
            Lru<String, Long> mine = users.get(userId(user));
            Long exp = mine == null ? null : mine.get(id(user, where));
            return exp != null && exp > now;
        }

        void add(String user, String where, long now, long ttl) {
            Lru<String, Long> mine = users.computeIfAbsent(userId(user), k -> new Lru<>(KNOWN_PER_USER));
            String id = id(user, where);
            Long old = mine.get(id);
            if (old == null || old < now + ttl / 2) {           // refresh at most every half-life: few writes
                mine.put(id, now + ttl);
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
        this.shares = new Lru<>(Math.max(16, this.maxTracked / 64));
        this.delayed = new Lru<>(this.maxTracked);
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        this.known = new KnownGood(k, knownUsers());
    }

    private int knownUsers() { return Math.max(1024, maxTracked / KNOWN_PER_USER); }

    /** Defaults: 10 failures per bucket in 60 s lock it for 60 s, doubling to 15 min; 50 fair-share checks per user. */
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

    private Shares sharesOf(String user, long now) {
        Shares s = shares.computeIfAbsent(user, k -> new Shares());
        if (now - s.windowStart > windowMs) {
            s.windowStart = now;
            s.checks = 0;
            s.usage.clear();
        }
        return s;
    }

    /** May {@code share} have a password checked for the user now, within its fair share? */
    private boolean fair(Shares s, String share) {
        int used = s.usage.getOrDefault(share, 0);
        int active = s.usage.size() + (used == 0 ? 1 : 0);
        return (long) (used + 1) * active <= maxPerUser && s.checks < 2L * maxPerUser;
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
                String share = Sources.share(src);
                if (!fair(sharesOf(user, now), share)) {
                    Delayed d = delayed.get(pairKey(share, user));
                    if (d == null) {                                  // its first time beyond the share starts its clock
                        d = new Delayed();
                        d.delayMs = delayedCheckMs;
                        d.nextCheck = now + d.delayMs;
                        delayed.put(pairKey(share, user), d);
                    }
                    if (d.nextCheck > now) wait = Math.max(wait, d.nextCheck - now);
                }
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
        String share = Sources.share(src);
        Shares sh = sharesOf(user, now);
        if (fair(sh, share)) {
            sh.usage.merge(share, 1, Integer::sum);
            sh.checks++;
        } else {                                                    // a delayed check that failed: wait longer
            Delayed d = delayed.computeIfAbsent(pairKey(share, user), k -> {
                Delayed n = new Delayed();
                n.delayMs = delayedCheckMs / 2;
                return n;
            });
            d.delayMs = Math.min(maxLockoutMs, Math.max(delayedCheckMs, d.delayMs * 2));
            d.nextCheck = now + d.delayMs;
        }
    }

    /**
     * Record a success: {@code user}'s failures at {@code source} are forgiven there (nobody else's), its share source is no
     * longer delayed for the user, and the user's address and bucket are remembered as known good.
     */
    public synchronized void success(String source, String user) {
        long now = clock.getAsLong();
        String src = Sources.normalize(source), b = Sources.bucket(src), a = Sources.address(src);
        String who = user == null ? "" : user;
        SourceState s = sources.get(b);
        if (s != null) {
            Integer mine = s.byUser.remove(who);
            if (mine != null) s.failures = Math.max(0, s.failures - mine);
            if (s.failures < maxPerSource) s.lockedUntil = 0;
            if (s.failures == 0 && s.byUser.isEmpty()) s.lockouts = 0;
        }
        if (user == null) return;
        PairState p = pairs.get(pairKey(a, user));
        if (p != null) {
            p.failures = 0;
            p.nextAllowed = 0;
        }
        delayed.remove(pairKey(Sources.share(src), user));
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

    /** The first delay of a share source beyond its fair share (it doubles with each failed delayed check). */
    public synchronized void delayedCheckMs(long ms) {
        this.delayedCheckMs = Math.max(1000L, Math.min(maxLockoutMs, ms));
    }

    /** Replace the known-good store (from {@code KnownGoodFile}): its HMAC key and entries (user id to entry id to expiry). */
    public synchronized void useKnownGood(byte[] hmacKey, Map<String, Map<String, Long>> entries) {
        KnownGood k = new KnownGood(hmacKey, knownUsers());
        long now = clock.getAsLong();
        for (Map.Entry<String, Map<String, Long>> u : entries.entrySet()) {
            Lru<String, Long> mine = new Lru<>(KNOWN_PER_USER);
            for (Map.Entry<String, Long> e : u.getValue().entrySet()) if (e.getValue() > now) mine.put(e.getKey(), e.getValue());
            if (!mine.isEmpty()) k.users.put(u.getKey(), mine);
        }
        this.known = k;
    }

    /** The known-good entries when they changed since the last call (unexpired ones only), else null. */
    public synchronized Map<String, Map<String, Long>> knownGoodIfChanged() {
        if (!known.dirty) return null;
        known.dirty = false;
        long now = clock.getAsLong();
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Lru<String, Long>> u : known.users.entrySet()) {
            Map<String, Long> mine = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : u.getValue().entrySet()) if (e.getValue() > now) mine.put(e.getKey(), e.getValue());
            if (!mine.isEmpty()) out.put(u.getKey(), mine);
        }
        return out;
    }

    synchronized int trackedSources() { return sources.size(); }
}
