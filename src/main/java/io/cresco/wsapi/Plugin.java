package io.cresco.wsapi;

import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;


import io.cresco.wsapi.auth.AuthRateLimiter;
import io.cresco.wsapi.auth.KnownGoodFile;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialFile;
import io.cresco.wsapi.netty.ApiGateway;
import io.cresco.wsapi.netty.NettyWsServer;
import io.cresco.wsapi.netty.OnBehalfGateway;
import io.cresco.wsapi.netty.WsRouter;
import io.cresco.wsapi.tls.WsapiKeystore;

import org.apache.felix.hc.api.HealthCheck;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component(
        service = { PluginService.class },
        scope=ServiceScope.PROTOTYPE,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property="wsapi=core",
        reference= { @Reference(name="io.cresco.library.agent.AgentService", service=AgentService.class)}
)
public class Plugin implements PluginService {

    /** Default wss/https listen port; overridable via config key "wsapi_port". */
    private static final int DEFAULT_WS_PORT = 8282;

    public BundleContext context;
    // Jetty instantiates the @ServerEndpoint / AuthFilter classes itself (not via DS), so they
    // reach the plugin through this static reference.
    public static PluginBuilder pluginBuilder;
    private io.cresco.library.plugin.Executor executor;
    private CLogger logger;
    // SLF4J fallback for the bootstrap window before the CLogger (pluginBuilder) is available.
    private static final Logger slog = LoggerFactory.getLogger(Plugin.class);
    private ConfigurationAdmin configurationAdmin;
    private Map<String,Object> map;

    private volatile KnownGoodFile knownGood;
    private volatile AuthRateLimiter limiter;
    private volatile java.util.concurrent.ScheduledExecutorService knownGoodSaver;

    // Assigned when the embedded Netty WebSocket server starts, so isStopped() can shut it down.
    private volatile NettyWsServer nettyServer;
    // Felix HealthCheck registration (central health); best-effort, unregistered on stop
    private ServiceRegistration<HealthCheck> healthReg;

    @Activate
    void activate(BundleContext context, Map<String,Object> map) {
        this.context = context;
        this.map = map;
    }

    @Reference
    protected void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    protected void unsetConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = null;
    }

    @Modified
    void modified(BundleContext context, Map<String,Object> map) {
        if (logger != null) {
            logger.info("Modified Config Map PluginID:" + (String) map.get("pluginID"));
        }
    }

    @Deactivate
    void deactivate(BundleContext context, Map<String,Object> map) {
        isStopped();
        this.context = null;
        this.map = null;
    }

    @Override
    public boolean isActive() {
        return pluginBuilder.isActive();
    }

    @Override
    public void setIsActive(boolean isActive) {
        pluginBuilder.setIsActive(isActive);
    }

    @Override
    public boolean inMsg(MsgEvent incoming) {
        pluginBuilder.msgIn(incoming);
        return true;
    }

    @Override
    public boolean isStarted() {
        try {

            if (pluginBuilder == null) {
                pluginBuilder = new PluginBuilder(this.getClass().getName(), context, map);
                this.logger = pluginBuilder.getLogger(Plugin.class.getName(), CLogger.Level.Info);
                this.executor = new PluginExecutor(pluginBuilder);
                pluginBuilder.setExecutor(executor);

                while (!pluginBuilder.getAgentService().getAgentState().isActive()) {
                    logger.info("Plugin " + pluginBuilder.getPluginID() + " waiting on Agent Init");
                    Thread.sleep(1000);
                }

                int wsPort = DEFAULT_WS_PORT;
                String portStr = pluginBuilder.getConfig().getStringParam("wsapi_port");
                if (portStr != null) {
                    try { wsPort = Integer.parseInt(portStr.trim()); } catch (NumberFormatException ignore) { }
                }
                // --- TLS identity: PKCS12, crypto-policy key, random owner-only password; fingerprint published ---
                io.cresco.library.plugin.Config cfg = pluginBuilder.getConfig();
                Path dataDir = Paths.get(pluginBuilder.getPluginDataDirectory());
                WsapiKeystore.Material tls;
                try {
                    tls = WsapiKeystore.loadOrCreate(tlsSettings(cfg, dataDir), new WsapiKeystore.Log() {
                        @Override public void info(String m) { logger.info(m); }
                        @Override public void warn(String m) { logger.warn(m); }
                    });
                } catch (WsapiKeystore.Refused refused) {
                    logger.error("wsapi NOT started: " + refused.getMessage());
                    pluginBuilder.setIsActive(false);
                    return false;
                }

                // --- authentication: per-user credentials (PBKDF2-HMAC-SHA-384) and/or the legacy service key ---
                String usersFile = cfg.getStringParam("wsapi_users_file");
                CredentialFile users = new CredentialFile(usersFile != null ? Paths.get(usersFile) : dataDir.resolve("ws.users"),
                        logger, System::currentTimeMillis);
                AuthRateLimiter limiter = new AuthRateLimiter(
                        cfg.getIntegerParam("wsapi_auth_max_failures", 10),
                        cfg.getIntegerParam("wsapi_auth_max_user_failures", 50),
                        1000L * cfg.getIntegerParam("wsapi_auth_window_seconds", 60),
                        1000L * cfg.getIntegerParam("wsapi_auth_lockout_seconds", 60),
                        1000L * cfg.getIntegerParam("wsapi_auth_max_lockout_seconds", 900),
                        100_000, System::currentTimeMillis);
                limiter.knownGoodTtlMs(24L * 3600_000L * Math.max(1, cfg.getIntegerParam("wsapi_auth_known_good_days", 30)));
                io.cresco.wsapi.auth.Sources.ipv6PrefixBits(cfg.getIntegerParam("wsapi_auth_ipv6_prefix_bits", 64));
                io.cresco.wsapi.auth.Sources.shareIpv4PrefixBits(cfg.getIntegerParam("wsapi_auth_share_ipv4_prefix_bits", 24));
                limiter.delayedCheckMs(1000L * cfg.getIntegerParam("wsapi_auth_delayed_check_seconds", 30));
                // the sources each user logged in from, kept across restarts (keyed hashes, owner-only files)
                this.knownGood = new KnownGoodFile(dataDir, m -> logger.warn(m));
                knownGood.load(limiter);
                this.limiter = limiter;
                this.knownGoodSaver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "wsapi-known-good");
                    t.setDaemon(true);
                    return t;
                });
                knownGoodSaver.scheduleWithFixedDelay(() -> {
                    try {
                        knownGood.saveIfChanged(limiter);
                    } catch (RuntimeException e) {
                        logger.warn("wsapi: saving the known-good sources failed: " + e.getMessage());
                    }
                }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
                Authenticator authenticator = new Authenticator(users::current,
                        () -> cfg.getStringParam("cresco_service_key"),
                        () -> cfg.getBooleanParam("wsapi_service_key_enabled", true),
                        this::agentTenant, this::superuserTenants, limiter,
                        1000L * cfg.getIntegerParam("wsapi_auth_cache_seconds", 300), System::currentTimeMillis)
                        .serviceKeyLimitsPerSource(cfg.getBooleanParam("wsapi_service_key_limits_per_source", true));
                int authThreads = cfg.getIntegerParam("wsapi_auth_threads",
                        Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)));
                String keyProblem = authenticator.serviceKeyProblem();
                if (keyProblem == null) {
                    logger.info("wsapi service key authentication is enabled");
                } else if (keyProblem.contains("too weak")) {
                    logger.error("wsapi: " + keyProblem + "; set a random key of at least 128 bits (e.g. openssl rand -hex 32), "
                            + "or use per-user credentials from " + users.path());
                } else {
                    logger.info("wsapi: " + keyProblem + "; only per-user credentials from " + users.path() + " can log in");
                }
                List<String> origins = new ArrayList<>();
                String originCsv = cfg.getStringParam("wsapi_allowed_origins");
                if (originCsv != null) for (String o : originCsv.split(",")) if (!o.isBlank()) origins.add(o.trim());
                WsRouter.Gate gate = new WsRouter.Gate(authenticator, apiGateway(), authThreads,
                        cfg.getIntegerParam("wsapi_auth_queue", 256), logger)
                        .limits(cfg.getIntegerParam("wsapi_auth_pending_per_source", 2),
                                cfg.getIntegerParam("wsapi_max_connections_per_user", 64),
                                cfg.getIntegerParam("wsapi_max_connections_per_admin", 256),
                                origins);

                // --- Netty WebSocket server (replaces Jetty). One wss listener serves all three
                //     endpoints; every upgrade authenticated; permessage-deflate off; whole-message frame aggregation. ---
                int ioBuf = cfg.getIntegerParam("dataplane_io_buffer_bytes", 64 * 1024);
                this.nettyServer = new NettyWsServer(pluginBuilder, wsPort, tls.keyStore, tls.password, ioBuf, gate);
                this.nettyServer.start();
                // let CONFIG 'nettuning' reach the live server tunables
                if (this.executor instanceof PluginExecutor) {
                    ((PluginExecutor) this.executor).setNettyServer(this.nettyServer);
                }

                logger.info("wsapi Netty WebSocket server started on wss port " + wsPort);

                pluginBuilder.setIsActive(true);

                // Register the central Felix HealthCheck (best-effort; must never break startup).
                registerHealthCheck(wsPort);
            }
            // started only while the server runs: after a refused start (keystore, bind) a later call must not say so
            return this.nettyServer != null;

        } catch (Exception ex) {
            if (logger != null) {
                logger.error("isStarted() failed to start wsapi Netty server", ex);
            } else {
                slog.error("isStarted() failed to start wsapi Netty server", ex);
            }
            return false;
        }
    }

    /** TLS settings from config (see {@link WsapiKeystore} and the README). */
    private WsapiKeystore.Settings tlsSettings(io.cresco.library.plugin.Config cfg, Path dataDir) {
        String pw = cfg.getStringParam("wsapi_keystore_password");     // a secret param: also _file / CRESCO_ env
        String ksFile = cfg.getStringParam("wsapi_keystore_file");
        List<String> sans = new ArrayList<>();
        String sanCsv = cfg.getStringParam("wsapi_tls_san");
        if (sanCsv != null) for (String x : sanCsv.split(",")) if (!x.isBlank()) sans.add(x.trim());
        return new WsapiKeystore.Settings()
                .dataDir(dataDir)
                .operatorKeystore(ksFile == null ? null : Paths.get(ksFile))
                .configuredPassword(pw == null ? null : pw.toCharArray())
                .keyType(cfg.getStringParam("wsapi_tls_key_type", "EC"))
                .rsaBits(cfg.getIntegerParam("wsapi_tls_rsa_bits", 3072))
                .validityDays(cfg.getIntegerParam("wsapi_tls_validity_days", 730))
                .replaceNonCompliant(cfg.getBooleanParam("wsapi_tls_replace_noncompliant", false))
                .keepLegacy(cfg.getBooleanParam("wsapi_tls_keep_legacy_keystore", false))
                .extraSans(sans)
                .identity(pluginBuilder.getRegion(), pluginBuilder.getAgent(), pluginBuilder.getPluginID());
    }

    /** API clients' messages go out on behalf of the authenticated client (the controller stamps api:<tenant>/<user>). */
    private ApiGateway apiGateway() {
        return new OnBehalfGateway(pluginBuilder);
    }

    /** This agent's tenant, from the controller; null while unknown, and wsapi then refuses every client (fail closed). */
    private String agentTenant() {
        try {
            return pluginBuilder.getAgentService().getTenant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The superuser tenants ({@code broker_superuser_tenants}), from the controller. */
    private Set<String> superuserTenants() {
        try {
            Set<String> s = pluginBuilder.getAgentService().getSuperuserTenants();
            return s == null ? Collections.emptySet() : s;
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
    }

    /** Register wsapi's Felix HealthCheck so CrescoHealthExecutor discovers it. Best-effort. */
    private void registerHealthCheck(int wsPort) {
        try {
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(HealthCheck.NAME, "wsapi");
            props.put(HealthCheck.TAGS, new String[]{"local"});
            healthReg = context.registerService(HealthCheck.class,
                    new WsapiHealthCheck(pluginBuilder, wsPort), props);
            logger.info("Registered wsapi HealthCheck (Felix HC)");
        } catch (Throwable t) {
            // health is best-effort: a missing hc.api bundle must never break wsapi
            if (logger != null) logger.warn("Could not register wsapi HealthCheck: " + t.getMessage());
        }
    }

    @Override
    public boolean isStopped() {
        try {
            if (healthReg != null) { healthReg.unregister(); healthReg = null; }
        } catch (Exception ignore) { }
        NettyWsServer server = this.nettyServer;
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ex) {
                if (logger != null) {
                    logger.error("wsapi embedded server shutdown error", ex);
                } else {
                    slog.error("wsapi embedded server shutdown error", ex);
                }
            }
        }
        this.nettyServer = null;
        if (knownGoodSaver != null) {
            knownGoodSaver.shutdownNow();
            knownGoodSaver = null;
        }
        if (knownGood != null && limiter != null) knownGood.saveIfChanged(limiter);

        if (pluginBuilder != null) {
            pluginBuilder.setExecutor(null);
            pluginBuilder.setIsActive(false);
        }
        return true;
    }

}
