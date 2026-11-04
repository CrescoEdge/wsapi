package io.cresco.wsapi.netty;

import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review (review/api-20261104): the address the rate limiter counts failures against is the router's
 * {@code sourceOf}, the full client address. One IPv6 host is normally given a whole /64, so it has 2^64 sources.
 */
class ReviewApiSourceKeyTest {

    static String source(String ip) throws Exception {
        return WsRouter.sourceOf(new InetSocketAddress(InetAddress.getByName(ip), 40000));
    }

    @Test
    void addressesOfOneIpv6Slash64ShareOneFailureBudget() throws Exception {
        Authenticator a = new Authenticator(CredentialStore::empty, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 300_000L, System::currentTimeMillis);
        for (int i = 0; i < 10; i++) {
            a.authenticate("Basic !!!", null, source("2001:db8:aa:bb::1"));
        }
        assertEquals(429, a.authenticate("Basic !!!", null, source("2001:db8:aa:bb::1")).status, "the address itself is limited");
        Authenticator.Result sibling = a.authenticate("Basic !!!", null, source("2001:db8:aa:bb::2"));
        assertEquals(429, sibling.status, "the next address of the same /64 starts with a fresh budget (HTTP "
                + sibling.status + "): the per-source limit is per /128");
    }
}
