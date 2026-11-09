package io.cresco.wsapi.auth;

import io.cresco.library.security.SecurityProfile;
import io.cresco.wsapi.netty.NettyWsServerAccess;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** wsapi follows the node's security profile (the controller's answer), and in FIPS mode the agent's FIPS TLS. */
class NodeProfileAndFipsTest {

    @Test
    void wsapisOwnSettingCanOnlyConfirmTheNodesProfile() {
        assertNull(io.cresco.wsapi.auth.SecurityProfile.conflict(null, SecurityProfile.PRODUCTION), "unset: the node decides");
        assertNull(io.cresco.wsapi.auth.SecurityProfile.conflict(" ", SecurityProfile.STANDARD));
        assertNull(io.cresco.wsapi.auth.SecurityProfile.conflict("Production", SecurityProfile.PRODUCTION));
        assertNull(io.cresco.wsapi.auth.SecurityProfile.conflict("standard", SecurityProfile.STANDARD));
        assertNull(io.cresco.wsapi.auth.SecurityProfile.conflict("standard", SecurityProfile.INSECURE), "insecure debug mode is no profile of its own");
        String c = io.cresco.wsapi.auth.SecurityProfile.conflict("production", SecurityProfile.STANDARD);
        assertNotNull(c, "wsapi configured production on a standard node does not start");
        assertTrue(c.contains("runs standard"), c);
        assertNotNull(io.cresco.wsapi.auth.SecurityProfile.conflict("standard", SecurityProfile.PRODUCTION), "nor the other way round");
        assertNotNull(io.cresco.wsapi.auth.SecurityProfile.conflict("prod", SecurityProfile.PRODUCTION), "not a profile");
    }

    @Test
    void aWsapiNotInProductionRefusesEveryLoginWhileItsNodeAnswersProduction() {
        boolean[] node = {false};
        Authenticator a = new Authenticator(() -> null, () -> "0123456789abcdef0123456789abcdef", () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis).nodeProfile(() -> node[0]);
        Authenticator.Result ok = a.authenticateFast(null, "0123456789abcdef0123456789abcdef", "10.0.0.1");
        assertNotNull(ok);
        assertEquals(0, ok.status, "a standard node: the service key logs in");
        node[0] = true;
        Authenticator.Result refused = a.authenticateFast(null, "0123456789abcdef0123456789abcdef", "10.0.0.1");
        assertEquals(503, refused.status, "the node now answers production: refused, whatever the credential");
        String basic = "Basic " + Base64.getEncoder().encodeToString("bob:pw".getBytes());
        assertEquals(503, a.authenticateFast(basic, null, "10.0.0.1").status);
    }

    @Test
    void fipsModeOffersTheAgentsFipsTlsVersions() {
        assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, NettyWsServerAccess.protocols(false));
        String before = System.getProperty("cresco.crypto.fips.tls");
        try {
            System.clearProperty("cresco.crypto.fips.tls");
            assertArrayEquals(new String[]{"TLSv1.3"}, NettyWsServerAccess.protocols(true), "SunJSSE in FIPS mode: TLS 1.3 only");
            System.setProperty("cresco.crypto.fips.tls", "TLSv1.3,TLSv1.2");
            assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, NettyWsServerAccess.protocols(true), "BCJSSE: 1.3 and 1.2");
            System.setProperty("cresco.crypto.fips.tls", "TLSv1,SSLv3");
            assertArrayEquals(new String[]{"TLSv1.3"}, NettyWsServerAccess.protocols(true), "never below 1.2");
        } finally {
            if (before == null) System.clearProperty("cresco.crypto.fips.tls");
            else System.setProperty("cresco.crypto.fips.tls", before);
        }
    }
}
