package io.cresco.wsapi;

import io.cresco.wsapi.websockets.APIDataPlane;
import io.cresco.wsapi.websockets.APILogStreamer;
import io.cresco.wsapi.websockets.APISocket;
import io.cresco.wsapi.websockets.AuthFilter;
import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;

import jakarta.servlet.DispatcherType;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.Executor;


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
    /** Default keystore/key password; overridable via config key "wsapi_keystore_password". */
    private static final String DEFAULT_KEYSTORE_PASSWORD = "cresco";

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

    // Assigned when the embedded Jetty 12 server starts, so isStopped() can actually stop it
    // (the previous build left the server in a local variable and leaked it on stop).
    private volatile Server jettyServer;

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
                String keystorePassword = pluginBuilder.getConfig().getStringParam("wsapi_keystore_password");
                if (keystorePassword == null) {
                    keystorePassword = DEFAULT_KEYSTORE_PASSWORD;
                }

                // Ensure a self-signed keystore exists for the wss connector.
                Path keyStorePath = Paths.get(pluginBuilder.getPluginDataDirectory() + File.separator + "ws.keystore");
                if (!keyStorePath.toFile().exists()) {
                    generateCertChainKeyStore(keyStorePath, keystorePassword.toCharArray());
                }

                // --- Jetty 12 thread pool (virtual threads for blocking work on JDK 21+) ---
                QueuedThreadPool threadPool = new QueuedThreadPool();
                threadPool.setName("wsapi");
                Executor virtualThreads = VirtualThreads.getDefaultVirtualThreadsExecutor();
                if (virtualThreads != null) {
                    threadPool.setVirtualThreadsExecutor(virtualThreads);
                }
                Server server = new Server(threadPool);

                // --- HTTPS connector on wsPort ---
                HttpConfiguration httpsConfig = new HttpConfiguration();
                SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
                // self-signed cert / arbitrary connect host: do not enforce SNI host matching
                secureRequestCustomizer.setSniHostCheck(false);
                httpsConfig.addCustomizer(secureRequestCustomizer);

                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath(keyStorePath.toString());
                sslContextFactory.setKeyStorePassword(keystorePassword);
                sslContextFactory.setKeyManagerPassword(keystorePassword);
                sslContextFactory.setIncludeProtocols("TLSv1.2", "TLSv1.3");

                ServerConnector sslConnector = new ServerConnector(server,
                        new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                        new HttpConnectionFactory(httpsConfig));
                sslConnector.setPort(wsPort);
                server.addConnector(sslConnector);
                server.setStopAtShutdown(true);

                // --- Servlet context + AuthFilter + Jakarta WebSocket endpoints ---
                ServletContextHandler servletContextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
                servletContextHandler.setContextPath("/");
                server.setHandler(servletContextHandler);

                servletContextHandler.addFilter(new FilterHolder(AuthFilter.class), "/*",
                        EnumSet.of(DispatcherType.REQUEST));

                JakartaWebSocketServletContainerInitializer.configure(servletContextHandler,
                        (servletContext, serverContainer) -> {
                            serverContainer.addEndpoint(APISocket.class);
                            serverContainer.addEndpoint(APIDataPlane.class);
                            serverContainer.addEndpoint(APILogStreamer.class);
                        });

                server.start();
                this.jettyServer = server;   // assign so isStopped() can shut it down (no more leak)

                logger.info("wsapi Jetty 12 server started on wss port " + wsPort
                        + " (virtualThreads=" + (virtualThreads != null) + ")");

                pluginBuilder.setIsActive(true);
            }
            return true;

        } catch (Exception ex) {
            if (logger != null) {
                logger.error("isStarted() failed to start wsapi Jetty server", ex);
            } else {
                slog.error("isStarted() failed to start wsapi Jetty server", ex);
            }
            return false;
        }
    }

    /**
     * Generate a self-signed RSA keypair + X.509 certificate and store it in a fresh keystore.
     * The plugin identity is split across three DN attributes (O=region, OU=agent, CN=plugin)
     * so each value stays within the X.509 64-char attribute limit; the previous build packed
     * region_agent_plugin into a single CN, which overflowed 64 chars and broke client parsing.
     */
    private void generateCertChainKeyStore(Path keyStorePath, char[] password) {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            Path pluginDataDir = Paths.get(pluginBuilder.getPluginDataDirectory());
            if (!pluginDataDir.toFile().exists()) {
                pluginDataDir.toFile().mkdirs();
            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, password);

            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
            keyPairGenerator.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter = notBefore.plus(365L * 5, ChronoUnit.DAYS);

            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.O, boundedRDN(pluginBuilder.getRegion()))
                    .addRDN(BCStyle.OU, boundedRDN(pluginBuilder.getAgent()))
                    .addRDN(BCStyle.CN, boundedRDN(pluginBuilder.getPluginID()))
                    .build();

            BigInteger serial = new BigInteger(159, new SecureRandom()); // positive, unpredictable

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    subject, serial, Date.from(notBefore), Date.from(notAfter), subject, keyPair.getPublic());
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            certBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider("BC").build(keyPair.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC")
                    .getCertificate(certBuilder.build(signer));

            ks.setKeyEntry("wsapi", keyPair.getPrivate(), password, new X509Certificate[]{cert});
            try (FileOutputStream fos = new FileOutputStream(keyStorePath.toString())) {
                ks.store(fos, password);
            }

        } catch (Exception ex) {
            if (logger != null) {
                logger.error("generateCertChainKeyStore() failed", ex);
            } else {
                slog.error("generateCertChainKeyStore() failed", ex);
            }
        }
    }

    /** Clamp an RDN value to the X.509 64-char attribute limit; never null/empty. */
    private static String boundedRDN(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    @Override
    public boolean isStopped() {
        Server server = this.jettyServer;
        if (server != null && !server.isStopped()) {
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
        this.jettyServer = null;

        if (pluginBuilder != null) {
            pluginBuilder.setExecutor(null);
            pluginBuilder.setIsActive(false);
        }
        return true;
    }

}
