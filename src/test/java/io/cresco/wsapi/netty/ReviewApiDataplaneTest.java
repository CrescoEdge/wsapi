package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.agent.AgentService;
import io.cresco.library.agent.AgentState;
import io.cresco.library.agent.ControllerMode;
import io.cresco.library.agent.ControllerState;
import io.cresco.library.data.DataPlaneService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.TestSupport;
import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import org.apache.activemq.command.ActiveMQBytesMessage;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.filter.BooleanExpression;
import org.apache.activemq.filter.MessageEvaluationContext;
import org.apache.activemq.selector.SelectorParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review (review/api-20261104): what an authenticated API client can do with the dataplane and the log
 * stream, driven through the real handlers (EmbeddedChannel) against a recording DataPlaneService and gateway.
 */
class ReviewApiDataplaneTest {

    /** What the handlers asked the dataplane to do. */
    static final class Recorder {
        final List<String> selectors = Collections.synchronizedList(new ArrayList<>());
        final List<Message> sent = Collections.synchronizedList(new ArrayList<>());
    }

    static PluginBuilder plugin(Recorder rec) throws Exception {
        DataPlaneService dps = (DataPlaneService) Proxy.newProxyInstance(DataPlaneService.class.getClassLoader(),
                new Class[]{DataPlaneService.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "addMessageListener":
                            rec.selectors.add((String) args[2]);
                            return "listener-" + rec.selectors.size();
                        case "createTextMessage": return new ActiveMQTextMessage();
                        case "createBytesMessage": return new ActiveMQBytesMessage();
                        case "sendMessage":
                            for (Object a : args) if (a instanceof Message) rec.sent.add((Message) a);
                            return true;
                        case "shardFor": case "getDataPlaneShardCount": return m.getName().equals("shardFor") ? 0 : 1;
                        case "hashCode": return 7;
                        case "equals": return proxy == args[0];
                        case "toString": return "RecordingDataPlane";
                        default: return m.getReturnType() == boolean.class ? false : null;
                    }
                });
        // client frames are published inline, so a test sees them in rec.sent as soon as writeInbound returns (wsapi
        // publishes them on its send threads; DataPlaneBackpressureTest covers that)
        return plugin(dps, Map.of("wsapi_dataplane_send_threads", "0"));
    }

    /** A wsapi plugin over {@code dps}, with {@code extra} configuration. */
    static PluginBuilder plugin(DataPlaneService dps, Map<String, Object> extra) throws Exception {
        Path dir = Files.createTempDirectory("wsapi-review-dp");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CLogger logger = TestSupport.logger(log);
        ControllerState cs = (ControllerState) Proxy.newProxyInstance(ControllerState.class.getClassLoader(),
                new Class[]{ControllerState.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "isActive": return true;
                        case "getControllerState": return ControllerMode.AGENT;
                        case "getRegion": case "getRegionalRegion": case "getGlobalRegion": return "region-1";
                        case "getAgent": case "getRegionalAgent": case "getGlobalAgent": return "agent-1";
                        default: return m.getReturnType() == boolean.class ? false : null;
                    }
                });
        AgentState state = new AgentState(cs);
        AgentService as = (AgentService) Proxy.newProxyInstance(AgentService.class.getClassLoader(),
                new Class[]{AgentService.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getAgentState": return state;
                        case "getCLogger": return logger;
                        case "getAgentDataDirectory": return dir.toString();
                        case "getDataPlaneService": return dps;
                        case "getTenant": return "tenant-a";
                        case "getSuperuserTenants": return Set.of();
                        case "hashCode": return 1;
                        case "equals": return proxy == args[0];
                        case "toString": return "ReviewAgentService";
                        default: return m.getReturnType() == boolean.class ? false : null;
                    }
                });
        Map<String, Object> config = new HashMap<>();
        config.put("pluginID", "plugin/9");
        config.putAll(extra);
        return new PluginBuilder(as, "io.cresco.wsapi.Plugin", null, config);
    }

    static Authenticator.Principal principal(String tenant, String user, String role) throws Exception {
        java.lang.reflect.Constructor<Authenticator.Principal> c = Authenticator.Principal.class
                .getDeclaredConstructor(String.class, String.class, String.class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(tenant, user, role, false);
    }

    /** A gate whose gateway records every RPC and answers status_code 7 (what the controller answers on success). */
    static WsRouter.Gate gate(PluginBuilder plugin, List<Object[]> rpcs) {
        Authenticator auth = new Authenticator(CredentialStore::empty, () -> null, () -> true, () -> "tenant-a", Set::of,
                AuthRateLimiter.defaults(), 0L, System::currentTimeMillis);
        ApiGateway gw = new ApiGateway() {
            @Override public void send(MsgEvent msg, Authenticator.Principal caller) { rpcs.add(new Object[]{msg, caller}); }
            @Override public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
                rpcs.add(new Object[]{msg, caller});
                msg.setParam("status_code", "7");
                return msg;
            }
        };
        return new WsRouter.Gate(auth, gw, 1, 4, plugin.getLogger("review", CLogger.Level.Info));
    }

    static boolean matches(String selector, ActiveMQMessage m) throws Exception {
        BooleanExpression e = SelectorParser.parse(selector);
        MessageEvaluationContext ctx = new MessageEvaluationContext();
        ctx.setMessageReference(m);
        return e.matches(ctx);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> reply(EmbeddedChannel ch) {
        Object o = ch.readOutbound();
        assertNotNull(o, "an activation reply");
        return new Gson().fromJson(((TextWebSocketFrame) o).text(), Map.class);
    }

    /**
     * The reserved caller names (cresco_caller, cresco_caller_tenant, cresco_caller_roles) are dropped from API
     * messages, but a dataplane client chooses the names of the two properties wsapi stamps on everything it publishes
     * (ident_key and io_type_key), so it publishes messages that claim to come from this agent's controller with the
     * infra role. Whatever stamps or reads callers on the dataplane, wsapi must not write a client's value there.
     */
    @Test
    void aDataplaneClientCannotWriteTheReservedCallerProperties() throws Exception {
        Recorder rec = new Recorder();
        PluginBuilder plugin = plugin(rec);
        List<Object[]> rpcs = Collections.synchronizedList(new ArrayList<>());
        EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, principal("tenant-a", "bob", "user"), gate(plugin, rpcs)));
        Map<String, String> hs = new HashMap<>();
        hs.put("ident_key", "cresco_caller");
        hs.put("ident_id", "controller:tenant-a/region-1/agent-1");
        hs.put("io_type_key", "cresco_caller_roles");
        hs.put("input_id", "infra");
        ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(hs)));
        Map<String, String> r = reply(ch);
        ch.writeInbound(new TextWebSocketFrame("hello from bob"));
        for (Message m : rec.sent) {
            assertNotEquals("controller:tenant-a/region-1/agent-1", m.getStringProperty("cresco_caller"),
                    "bob's dataplane message names this agent's controller as its caller (activation " + r + ")");
            assertNotEquals("infra", m.getStringProperty("cresco_caller_roles"),
                    "bob's dataplane message carries the infra role (activation " + r + ")");
        }
    }

    /**
     * Role separation stops at the dataplane. streamfile is an admin-only action, but its bytes travel on the tenant's
     * dataplane, and an ordinary user subscribes with a selector anchored on a value every transfer carries
     * (seq_num='0', offset='0', transfer_end='true'; with dataplane_shards=1, the default, there is one topic). It then
     * receives the first chunk (with the transfer id) of every admin's download, and can follow it with
     * transfer_id='<id>'. The same holds for every process's stdout (type='output').
     */
    @Test
    void anOrdinaryUserCannotSubscribeToOtherCallersTransfersAndOutput() throws Exception {
        String[] queries = {"seq_num = '0'", "offset = '0'", "transfer_end = 'true'", "type = 'output'"};
        ActiveMQBytesMessage adminChunk = new ActiveMQBytesMessage();
        adminChunk.setStringProperty("stream_name", "sf-0123456789abcdef0123456789abcdef");
        adminChunk.setStringProperty("transfer_id", "pc-0123456789abcdef01234567");
        adminChunk.setStringProperty("seq_num", "0");
        adminChunk.setStringProperty("offset", "0");
        ActiveMQBytesMessage adminEnd = new ActiveMQBytesMessage();
        adminEnd.setStringProperty("stream_name", "sf-0123456789abcdef0123456789abcdef");
        adminEnd.setStringProperty("transfer_end", "true");
        ActiveMQTextMessage adminStdout = new ActiveMQTextMessage();
        adminStdout.setStringProperty("stream_name", "admin-job-7");
        adminStdout.setStringProperty("type", "output");
        adminStdout.setText("db password is hunter2");

        List<String> leaks = new ArrayList<>();
        for (String q : queries) {
            Recorder rec = new Recorder();
            PluginBuilder plugin = plugin(rec);
            EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin, principal("tenant-a", "bob", "user"),
                    gate(plugin, new ArrayList<>())));
            ch.writeInbound(new TextWebSocketFrame(q));
            Map<String, String> r = reply(ch);
            if (!"10".equals(r.get("status_code"))) continue;     // refused: no leak through this query
            String sel = rec.selectors.get(0);
            for (ActiveMQMessage m : new ActiveMQMessage[]{adminChunk, adminEnd, adminStdout}) {
                if (matches(sel, m)) leaks.add(q + " -> " + m.getStringProperty("stream_name"));
            }
        }
        assertTrue(leaks.isEmpty(), "a user-role client received streams it did not name: " + leaks);
    }

    /**
     * The log stream sends setlogdp and setloglevel for its client to any region/agent and any base class, at any
     * level. The controller's AgentExecutor declares both actions without roles (open to every caller of the tenant),
     * so an ordinary user raises io.cresco to Trace and reads what the agent logs there: every routed MsgEvent's params
     * (MsgRouter/AgentConsumer trace) and every line typed into a process's stdin (executor Runner debug "stdin -> ").
     * That bypasses the dataplane's no-stdin rule and the role checks wsapi relies on.
     */
    @Test
    void anOrdinaryUserCannotTurnOnTraceLoggingThroughTheLogStream() throws Exception {
        Recorder rec = new Recorder();
        PluginBuilder plugin = plugin(rec);
        List<Object[]> rpcs = Collections.synchronizedList(new ArrayList<>());
        EmbeddedChannel ch = new EmbeddedChannel(new LogStreamerWsHandler(plugin, principal("tenant-a", "bob", "user"), gate(plugin, rpcs)));
        ch.writeInbound(new TextWebSocketFrame("region-1,agent-1,Trace,io.cresco"));
        List<String> sentForBob = new ArrayList<>();
        for (Object[] r : rpcs) {
            MsgEvent m = (MsgEvent) r[0];
            sentForBob.add(m.getParam("action") + (m.getParam("loglevel") != null ? "=" + m.getParam("loglevel") : ""));
        }
        assertFalse(sentForBob.contains("setloglevel=Trace"),
                "wsapi sent " + sentForBob + " on behalf of user bob (role user)");
    }
}
