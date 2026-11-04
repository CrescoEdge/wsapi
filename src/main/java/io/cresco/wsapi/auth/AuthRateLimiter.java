package io.cresco.wsapi.auth;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limits failed wsapi authentications, per source address and per user name.
 *
 * <p>A source with {@code maxPerSource} failures inside {@code windowMs} is refused (HTTP 429) without its credentials
 * being checked until {@code lockoutMs} has passed; each further lockout of the same source doubles, up to
 * {@code maxLockoutMs}. A user name with {@code maxPerUser} failures inside the window (guessing from many addresses)
 * is locked the same way. A success clears the source's count. Memory is bounded: at most {@code maxTracked}
 * entries per map; when full, expired entries are dropped first and then new keys share one overflow bucket, which
 * fails closed (the bucket locks like any source).
 */
public final class AuthRateLimiter {

    private static final String OVERFLOW = "\u0000overflow";

    private final int maxPerSource;
    private final int maxPerUser;
    private final long windowMs;
    private final long lockoutMs;
    private final long maxLockoutMs;
    private final int maxTracked;
    private final LongSupplier clock;

    private final Map<String, Counter> sources = new ConcurrentHashMap<>();
    private final Map<String, Counter> users = new ConcurrentHashMap<>();

    private static final class Counter {
        long windowStart;
        int failures;
        long lockedUntil;
        int lockouts;
        long lastSeen;
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
    }

    /** Defaults: 10 failures per source or 50 per user within 60 s lock for 60 s, doubling to 15 min. */
    public static AuthRateLimiter defaults() {
        return new AuthRateLimiter(10, 50, 60_000L, 60_000L, 15 * 60_000L, 10_000, System::currentTimeMillis);
    }

    /** Milliseconds until {@code source} (or {@code user}, when not null) may try again; 0 when it may try now. */
    public long retryAfterMs(String source, String user) {
        long now = clock.getAsLong();
        long wait = waitOf(sources, source, now);
        if (user != null) wait = Math.max(wait, waitOf(users, user, now));
        return wait;
    }

    /** Record a failed attempt. */
    public void failure(String source, String user) {
        long now = clock.getAsLong();
        bump(sources, source, maxPerSource, now);
        if (user != null) bump(users, user, maxPerUser, now);
    }

    /** Record a success: the source's failures are forgiven (a user lock stays until it expires). */
    public void success(String source) {
        Counter c = sources.get(key(sources, source, false));
        if (c != null) {
            synchronized (c) {
                c.failures = 0;
                c.lockouts = 0;
            }
        }
    }

    private long waitOf(Map<String, Counter> map, String k, long now) {
        Counter c = map.get(key(map, k, false));
        if (c == null) return 0L;
        synchronized (c) {
            return Math.max(0L, c.lockedUntil - now);
        }
    }

    private void bump(Map<String, Counter> map, String k, int max, long now) {
        Counter c = map.computeIfAbsent(key(map, k, true), x -> new Counter());
        synchronized (c) {
            c.lastSeen = now;
            if (now - c.windowStart > windowMs) {
                c.windowStart = now;
                c.failures = 0;
            }
            c.failures++;
            if (c.failures >= max) {
                long lock = Math.min(maxLockoutMs, lockoutMs << Math.min(20, c.lockouts));
                c.lockedUntil = now + lock;
                c.lockouts++;
                c.failures = 0;
                c.windowStart = now;
            }
        }
    }

    private String key(Map<String, Counter> map, String k, boolean creating) {
        String key = k == null ? "" : k;
        if (map.containsKey(key)) return key;
        if (map.size() >= maxTracked) {
            if (creating) prune(map);
            if (map.size() >= maxTracked) return OVERFLOW;   // an untracked key shares (and is judged by) the overflow bucket
        }
        return key;
    }

    private void prune(Map<String, Counter> map) {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<String, Counter>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Counter> e = it.next();
            Counter c = e.getValue();
            synchronized (c) {
                if (c.lockedUntil <= now && now - c.lastSeen > windowMs) it.remove();
            }
        }
    }

    int trackedSources() { return sources.size(); }
}
