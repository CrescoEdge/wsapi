package io.cresco.wsapi.netty;

import io.cresco.library.capability.CrescoAction;
import io.cresco.library.capability.CrescoActions;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.security.Caller;
import io.cresco.wsapi.PluginExecutor;
import io.cresco.wsapi.TestSupport;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** API clients' messages go out on behalf of the client, with the caller the controller will stamp. */
class OnBehalfGatewayTest {

    static Authenticator.Principal principal(String tenant, String user, String role) throws Exception {
        // the same constructor path the Authenticator uses: authenticate the service key against a stub
        Authenticator a = new Authenticator(CredentialStore::empty, () -> "k-k-k-k-k-k", () -> true, () -> tenant, Set::of,
                AuthRateLimiter.defaults(), 0, System::currentTimeMillis);
        Authenticator.Principal sk = a.authenticate(null, "k-k-k-k-k-k", "s").principal;
        if (user.equals(sk.user) && role.equals(sk.role)) return sk;
        java.lang.reflect.Constructor<Authenticator.Principal> c = Authenticator.Principal.class
                .getDeclaredConstructor(String.class, String.class, String.class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(tenant, user, role, false);
    }

    static PluginBuilder plugin(Path dir, List<Object[]> sent) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("pluginID", "plugin/9");
        return TestSupport.pluginBuilder(dir, cfg, Collections.synchronizedList(new ArrayList<>()), sent == null ? null : sent::add, "tenant-a");
    }

    @Test
    void messagesGoOutForTheClientWithItsTenantAndRole(@TempDir Path dir) throws Exception {
        List<Object[]> sent = Collections.synchronizedList(new ArrayList<>());
        PluginBuilder pb = plugin(dir, sent);
        OnBehalfGateway g = new OnBehalfGateway(pb);
        MsgEvent m = pb.getGlobalControllerMsgEvent(MsgEvent.Type.EXEC);
        m.setParam("action", "listregions");
        g.send(m, principal("tenant-a", "bob", "user"));
        Caller c = (Caller) sent.get(0)[2];
        assertEquals("plugin/9", sent.get(0)[0]);
        assertSame(m, sent.get(0)[1]);
        assertEquals("api:tenant-a/bob", c.getPrincipal());
        assertEquals("tenant-a", c.getTenant());
        assertEquals(Set.of("user"), c.getRoles());
        assertEquals(Caller.Kind.API, c.getKind());

        Caller sk = OnBehalfGateway.callerOf(principal("tenant-a", "service-key", "admin"));
        assertEquals("api:tenant-a/service-key", sk.getPrincipal());
        assertEquals(Set.of("admin"), sk.getRoles());
    }

    @Test
    void anRpcGoesOutForTheClientToo(@TempDir Path dir) throws Exception {
        List<Object[]> sent = Collections.synchronizedList(new ArrayList<>());
        PluginBuilder pb = plugin(dir, sent);
        MsgEvent m = pb.getGlobalControllerMsgEvent(MsgEvent.Type.EXEC);
        m.setParam("action", "listregions");
        assertNull(new OnBehalfGateway(pb).rpc(m, 1000, principal("tenant-a", "alice", "admin")), "no controller answers here");
        assertEquals("api:tenant-a/alice", ((Caller) sent.get(0)[2]).getPrincipal());
        assertEquals("true", m.getParam("is_rpc"));
    }

    @Test
    void aControllerThatRefusesOnBehalfSendingSendsNothing(@TempDir Path dir) throws Exception {
        PluginBuilder pb = plugin(dir, null);   // the library default: refuse
        MsgEvent m = pb.getGlobalControllerMsgEvent(MsgEvent.Type.EXEC);
        assertThrows(SecurityException.class, () -> new OnBehalfGateway(pb).send(m, principal("tenant-a", "bob", "user")));
    }

    @Test
    void liveNetworkTuningNeedsInfraOrAdmin() {
        for (CrescoAction a : PluginExecutor.class.getAnnotation(CrescoActions.class).value()) {
            if (a.name().equals("nettuning")) {
                assertEquals(Set.of("infra", "admin"), Set.of(a.roles()));
                return;
            }
        }
        fail("nettuning not declared");
    }
}
