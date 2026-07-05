package io.cresco.wsapi;

import io.cresco.library.plugin.PluginBuilder;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;

/**
 * Central health for wsapi. Registered as an {@code org.apache.felix.hc.api.HealthCheck} OSGi
 * service (name "wsapi", tag "local") so the controller's CrescoHealthExecutor discovers and
 * schedules it alongside the built-in checks — the same Felix Health Check system every other
 * Cresco bundle uses. wsapi is the mesh's wss entry point; an active plugin with the Netty server
 * bound is healthy. Registered only after the server starts, so active implies listening.
 * Self-guards while the plugin is still coming up.
 */
public class WsapiHealthCheck implements HealthCheck {

    private final PluginBuilder plugin;
    private final int wsPort;

    public WsapiHealthCheck(PluginBuilder plugin, int wsPort) {
        this.plugin = plugin;
        this.wsPort = wsPort;
    }

    @Override
    public Result execute() {
        try {
            if (plugin == null || !plugin.isActive()) {
                return new Result(Result.Status.TEMPORARILY_UNAVAILABLE, "wsapi not active");
            }
            return new Result(Result.Status.OK, "wsapi OK: wss listening on port " + wsPort);
        } catch (Exception ex) {
            return new Result(Result.Status.WARN, "wsapi health error: " + ex.getMessage());
        }
    }
}
