package io.cresco.wsapi;

import io.cresco.wsapi.websockets.APIDataPlane;
import io.cresco.wsapi.websockets.APILogStreamer;
import io.cresco.wsapi.websockets.APISocket;
import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.websockets.AuthFilter;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.x509.X509V1CertificateGenerator;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.joda.time.DateTime;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;

import javax.security.auth.x500.X500Principal;
import javax.servlet.DispatcherType;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;


@Component(
        service = { PluginService.class },
        scope=ServiceScope.PROTOTYPE,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property="wsapi=core",
        reference= { @Reference(name="io.cresco.library.agent.AgentService", service=AgentService.class)}
)

public class Plugin implements PluginService {

    //public PluginBuilder getPluginBuilder() { return  pluginBuilder; }

    public BundleContext context;
    public static PluginBuilder pluginBuilder;
    private Executor executor;
    private CLogger logger;
    //private HttpService server;
    public String repoPath = null;
    private ConfigurationAdmin configurationAdmin;
    private Map<String,Object> map;
    private Server jettyServer;
    private ServerContainer wscontainer;

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
        System.out.println("Modified Config Map PluginID:" + (String) map.get("pluginID"));
    }

    @Deactivate
    void deactivate(BundleContext context, Map<String,Object> map) {

        isStopped();

        if(this.context != null) {
            this.context = null;
        }

        if(this.map != null) {
            this.map = null;
        }

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

    private Dictionary<String, String> getJerseyServletParams() {
        Dictionary<String, String> jerseyServletParams = new Hashtable<>();
        jerseyServletParams.put("javax.ws.rs.Application", Plugin.class.getName());
        return jerseyServletParams;
    }

    private String getRepoPath() {
        String path = null;
        try {
            //todo create seperate director for repo
            path = new File(Plugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();

        } catch(Exception ex) {
            //logger.error(ex.getMessage());
            ex.printStackTrace();
        }
        return path;
    }

    @Override
    public boolean isStarted() {
        try {

            if(pluginBuilder == null) {
                pluginBuilder = new PluginBuilder(this.getClass().getName(), context, map);
                this.logger = pluginBuilder.getLogger(Plugin.class.getName(), CLogger.Level.Info);
                this.executor = new PluginExecutor(pluginBuilder);
                pluginBuilder.setExecutor(executor);

                while (!pluginBuilder.getAgentService().getAgentState().isActive()) {
                    logger.info("Plugin " + pluginBuilder.getPluginID() + " waiting on Agent Init");
                    //System.out.println("Plugin " + pluginBuilder.getPluginID() + " waiting on Agent Init");
                    Thread.sleep(1000);
                }

                // use secure sockets
                Server server = new Server();
                HttpConfiguration https = new HttpConfiguration();
                https.addCustomizer(new SecureRequestCustomizer());


                SslContextFactory sslContextFactory = new SslContextFactory.Server();

                Path kyStorePath = Paths.get(pluginBuilder.getPluginDataDirectory() + File.separator + "ws.keystore");

                try {
                    if (!kyStorePath.toFile().exists()) {
                        generateCertChainKeyStore(kyStorePath);
                    }
                } catch (Exception ex) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ex.printStackTrace(pw);
                    logger.error(pw.toString());
                }


                //KeyStore ks = KeyStore.getInstance("JKS");
                //ks.load(getClass().getClassLoader().getResourceAsStream("ws.keystore"), "cresco".toCharArray());

                //sslContextFactory.setKeyStore(ks);
                //sslContextFactory.setKeyStorePath("/Users/cody/IdeaProjects/wsapi/ssl/ws.keystore");
                sslContextFactory.setKeyStorePath(kyStorePath.toString());
                sslContextFactory.setKeyStorePassword("cresco");
                sslContextFactory.setKeyManagerPassword("cresco");
                ServerConnector sslConnector = new ServerConnector(server,
                        new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                        new HttpConnectionFactory(https));
                //sslConnector.setHost(serverName); // EDIT: this line was the problem, removing it fixed everything.
                sslConnector.setPort(8282);
                server.setConnectors(new Connector[] { sslConnector });

                server.setStopAtShutdown(true);
                server.setDumpAfterStart(false);
                server.setDumpBeforeStop(false);


                // Initialize JSR-356 style websocket
                ServletContextHandler servletContextHandler =
                        new ServletContextHandler(ServletContextHandler.SESSIONS);
                servletContextHandler.setContextPath("/");
                server.setHandler(servletContextHandler);

                EnumSet<DispatcherType> SCOPE = EnumSet.of(DispatcherType.REQUEST);
                // Jetty DoSFilter, wrapped so we can set init parameters
                /*
                FilterHolder holder = new FilterHolder( DoSFilter.class );
                // see DoSFilter Javadoc for names and meanings of init parameters
                holder.setInitParameter("maxRequestsPerSec", "100"); // "1" for testing
                holder.setInitParameter("delayMs", "200"); // "-1" to reject excess request
                holder.setInitParameter("remotePort", "false"); // "true" may be useful
                servletContextHandler.addFilter( holder, "/*", SCOPE );
                 */

                FilterHolder auth = new FilterHolder( AuthFilter.class );
                servletContextHandler.addFilter( auth, "/*", SCOPE );

                ServerContainer container =
                        WebSocketServerContainerInitializer.configureContext(servletContextHandler);

                container.addEndpoint(APISocket.class);
                container.addEndpoint(APIDataPlane.class);
                container.addEndpoint(APILogStreamer.class);


                server.start();
                logger.info("Started server: " + server);
                if (server.getConnectors().length > 0) {
                    logger.info("Connector = " + server.getConnectors()[0] +
                            " isRunning=" + server.getConnectors()[0].isRunning());
                }

                pluginBuilder.setIsActive(true);


            }
            return true;

        } catch(Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    private KeyPair generateKeyPair() throws NoSuchAlgorithmException, NoSuchProviderException {
        KeyPairGenerator kpGen = KeyPairGenerator.getInstance("RSA", "BC");
        kpGen.initialize(1024, new SecureRandom());
        return kpGen.generateKeyPair();
    }


    private void generateCertChainKeyStore_old(Path kyStorePath) {

        try {

            Path pluginDataDir = Paths.get(pluginBuilder.getPluginDataDirectory());
            if (!pluginDataDir.toFile().exists()) {

                pluginDataDir.toFile().mkdirs();

            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

            char[] password = "cresco".toCharArray();
            ks.load(null, password);


            // Create self signed Root CA certificate

            String agentName = "wsapi-" + UUID.randomUUID();

            //start gen
            KeyPair rootCAKeyPair = generateKeyPair();

            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name("CN=rootCA-" + agentName), // issuer authority
                    BigInteger.valueOf(new Random().nextInt()), //serial number of certificate
                    DateTime.now().toDate(), // start of validity
                    new DateTime().plusYears(3).toDate(),
                    //new DateTime(2025, 12, 31, 0, 0, 0, 0).toDate(), //end of certificate validity
                    new X500Name("CN=rootCA-" + agentName), // subject name of certificate
                    rootCAKeyPair.getPublic()); // public key of certificate
            // key usage restrictions
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
            builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(true));
            X509Certificate rootCA = new JcaX509CertificateConverter().getCertificate(builder
                    .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").
                            build(rootCAKeyPair.getPrivate()))); // private key of signing authority , here it is self signed

            X509Certificate[] chain = new X509Certificate[1];
            chain[0]=rootCA;
            //

            ks.setKeyEntry("wsapi", rootCAKeyPair.getPrivate(), password, chain);

            // Store away the keystore.
            FileOutputStream fos = new FileOutputStream(kyStorePath.toString());
            ks.store(fos, password);
            fos.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    private void generateCertChainKeyStore(Path kyStorePath) {

        try {

            Path pluginDataDir = Paths.get(pluginBuilder.getPluginDataDirectory());
            if (!pluginDataDir.toFile().exists()) {

                pluginDataDir.toFile().mkdirs();

            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

            char[] password = "cresco".toCharArray();
            ks.load(null, password);


            // Create self signed Root CA certificate

            String agentName = "wsapi-" + UUID.randomUUID();

            // yesterday
            //Date validityBeginDate = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
            // in 2 years
            //Date validityEndDate = new Date(System.currentTimeMillis() + 25 * 365 * 24 * 60 * 60 * 1000);

            DateTime validityBeginDate = DateTime.now().minusDays(1);
            DateTime validityEndDate = validityBeginDate.plusYears(5);



            // GENERATE THE PUBLIC/PRIVATE RSA KEY PAIR
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
            keyPairGenerator.initialize(1024, new SecureRandom());

            java.security.KeyPair keyPair = keyPairGenerator.generateKeyPair();

            // GENERATE THE X509 CERTIFICATE
            X509V1CertificateGenerator certGen = new X509V1CertificateGenerator();
            X500Principal dnName = new X500Principal("CN=rootCA-" + agentName);

            certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
            certGen.setSubjectDN(dnName);
            certGen.setIssuerDN(dnName); // use the same
            certGen.setNotBefore(validityBeginDate.toDate());
            certGen.setNotAfter(validityEndDate.toDate());
            certGen.setPublicKey(keyPair.getPublic());
            certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

            X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");

            X509Certificate[] chain = new X509Certificate[1];
            chain[0]=cert;

            ks.setKeyEntry("wsapi", keyPair.getPrivate(), password, chain);

            // Store away the keystore.
            FileOutputStream fos = new FileOutputStream(kyStorePath.toString());
            ks.store(fos, password);
            fos.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }


    @Override
    public boolean isStopped() {



        if(wscontainer != null) {
            if(!wscontainer.isStopped()) {
                try {

                    wscontainer.stop();
                    while(!wscontainer.isStopped()) {
                        logger.error("Waiting on WSAPI (wscontainer) to stop.");
                    }

                } catch (Exception ex) {
                    logger.error("embedded web server shutdown error : " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        }

        if(jettyServer != null) {
            if(!jettyServer.isStopped()) {
                try {

                    jettyServer.stop();
                    while(!jettyServer.isStopped()) {
                        logger.error("Waiting on WSAPI (server) to stop.");
                    }

                } catch (Exception ex) {
                    logger.error("embedded web server shutdown error : " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        }

        if(pluginBuilder != null) {
            pluginBuilder.setExecutor(null);
            pluginBuilder.setIsActive(false);
        }
        return true;
    }

}