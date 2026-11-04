package io.cresco.wsapi;

import io.cresco.library.agent.AgentService;
import io.cresco.library.agent.AgentState;
import io.cresco.library.agent.ControllerMode;
import io.cresco.library.agent.ControllerState;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A PluginBuilder backed by a stand-in AgentService (no OSGi, no controller), for wsapi tests. */
public final class TestSupport {

    private TestSupport() {}

    /** A CLogger that records "LEVEL message" lines. */
    public static CLogger logger(List<String> sink) {
        InvocationHandler h = (proxy, m, args) -> {
            String name = m.getName();
            if (args != null && args.length > 0 && args[0] instanceof String
                    && (name.equals("error") || name.equals("warn") || name.equals("info") || name.equals("debug") || name.equals("trace"))) {
                sink.add(name.toUpperCase() + " " + args[0]);
            }
            if (m.getReturnType() == boolean.class) return false;
            return null;
        };
        return (CLogger) Proxy.newProxyInstance(CLogger.class.getClassLoader(), new Class[]{CLogger.class}, h);
    }

    public static PluginBuilder pluginBuilder(Path agentDataDir, Map<String, Object> config, List<String> log) {
        return pluginBuilder(agentDataDir, config, log, null, null);
    }

    /**
     * @param onBehalf receives {@code [pluginId, msg, caller]} for every msgOutOnBehalfOf; null keeps the library default
     *                 (the controller refuses with SecurityException)
     * @param tenant   what AgentService.getTenant answers
     */
    public static PluginBuilder pluginBuilder(Path agentDataDir, Map<String, Object> config, List<String> log,
                                              java.util.function.Consumer<Object[]> onBehalf, String tenant) {
        CLogger logger = logger(log);
        ControllerState cs = (ControllerState) Proxy.newProxyInstance(ControllerState.class.getClassLoader(),
                new Class[]{ControllerState.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "isActive": return true;
                        case "getControllerState": return ControllerMode.AGENT;
                        case "getRegion": case "getRegionalRegion": case "getGlobalRegion": return "region-1";
                        case "getAgent": case "getRegionalAgent": case "getGlobalAgent": return "agent-1";
                        case "isRegionalController": case "isGlobalController": return false;
                        default: return null;
                    }
                });
        AgentState state = new AgentState(cs);
        AgentService as = (AgentService) Proxy.newProxyInstance(AgentService.class.getClassLoader(),
                new Class[]{AgentService.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getAgentState": return state;
                        case "getCLogger": return logger;
                        case "getAgentDataDirectory": return agentDataDir.toString();
                        case "getTenant": return tenant;
                        case "getSuperuserTenants": return java.util.Set.of("ops");
                        case "msgOutOnBehalfOf":
                            if (onBehalf == null) return InvocationHandler.invokeDefault(proxy, m, args);
                            onBehalf.accept(args);
                            return null;
                        case "toString": return "TestAgentService";
                        case "hashCode": return 1;
                        case "equals": return proxy == args[0];
                        default:
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                    }
                });
        return new PluginBuilder(as, "io.cresco.wsapi.Plugin", null, config);
    }
}
