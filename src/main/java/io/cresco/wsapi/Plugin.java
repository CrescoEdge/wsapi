package io.cresco.wsapi;

import io.cresco.wsapi.websockets.APIDataPlane;
import io.cresco.wsapi.websockets.APISocket;
import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;

import javax.servlet.DispatcherType;
import java.io.File;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.Hashtable;
import java.util.Map;

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
    private ServletHolder jerseyServlet;

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


                ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
                context.setContextPath("/");


                jettyServer = new Server(8282);
                jettyServer.setHandler(context);


                // add filters
                EnumSet<DispatcherType> SCOPE = EnumSet.of(DispatcherType.REQUEST);
                // Jetty DoSFilter, wrapped so we can set init parameters
                FilterHolder holder = new FilterHolder( DoSFilter.class );
                // see DoSFilter Javadoc for names and meanings of init parameters
                holder.setInitParameter("maxRequestsPerSec", "100"); // "1" for testing
                holder.setInitParameter("delayMs", "200"); // "-1" to reject excess request
                holder.setInitParameter("remotePort", "false"); // "true" may be useful
                context.addFilter( holder, "/*", SCOPE );

                //context.addServlet(jerseyServlet, "/*");
                //context.addServlet(MyEchoServlet.class, "/*");

                ServerContainer wscontainer = WebSocketServerContainerInitializer.configureContext(context);

                //javax.websocket.Session.setMaxTextMessageBufferSize(int)
                // Add WebSocket endpoint to javax.websocket layer
                //wscontainer.addEndpoint(LogSocket.class);
                wscontainer.addEndpoint(APISocket.class);
                wscontainer.addEndpoint(APIDataPlane.class);

                //startWS();

                try {
                    jettyServer.start();
                    //jettyServer.join();
                } catch (Exception e) {
                   logger.error("Could not start embedded web server");
                    e.printStackTrace();
                }

                pluginBuilder.setIsActive(true);


            }
            return true;

        } catch(Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }


    @Override
    public boolean isStopped() {

        if(jettyServer != null) {
            if(!jettyServer.isStopped()) {
                try {

                    jettyServer.stop();
                    while(!jettyServer.isStopped()) {
                        logger.error("Waiting on Dashboard to stop.");
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