package io.cresco.wsapi;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.metrics.CMetric;
import io.cresco.library.metrics.MeasurementEngine;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.netty.DataPlaneWsHandler;

public class PluginExecutor implements Executor {

    private PluginBuilder plugin;
    CLogger logger;
    private volatile io.cresco.wsapi.netty.NettyWsServer nettyServer;

    // B-2 unified metrics: wsapi's dataplane connection/throughput counters as MeasurementEngine gauges.
    private final Gson gson = new Gson();
    private MeasurementEngine metricEngine;

    public PluginExecutor(PluginBuilder pluginBuilder) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(PluginExecutor.class.getName(),CLogger.Level.Info);
    }

    private synchronized String getMetricsJson() {
        try {
            if (metricEngine == null) {
                metricEngine = new MeasurementEngine(plugin);
                metricEngine.setGauge("wsapi.dataplane.connections", "active dataplane WebSocket connections", "wsapi", CMetric.MeasureClass.GAUGE_INT);
                metricEngine.setGauge("wsapi.dataplane.bytes", "total dataplane bytes ingested", "wsapi", CMetric.MeasureClass.GAUGE_LONG);
                metricEngine.setGauge("wsapi.dataplane.messages", "total dataplane messages ingested", "wsapi", CMetric.MeasureClass.GAUGE_LONG);
            }
            metricEngine.updateIntGauge("wsapi.dataplane.connections", DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
            metricEngine.updateLongGauge("wsapi.dataplane.bytes", DataPlaneWsHandler.DATAPLANE_BYTES.get());
            metricEngine.updateLongGauge("wsapi.dataplane.messages", DataPlaneWsHandler.DATAPLANE_MESSAGES.get());
            return gson.toJson(metricEngine.getAllMetrics());
        } catch (Exception ex) {
            logger.error("getMetricsJson() " + ex.getMessage());
            return "{}";
        }
    }

    /** Wired by Plugin after the server starts, so CONFIG nettuning can reach the live server tunables. */
    public void setNettyServer(io.cresco.wsapi.netty.NettyWsServer nettyServer) {
        this.nettyServer = nettyServer;
    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {
        try {
            if ("nettuning".equals(incoming.getParam("action")) && nettyServer != null) {
                // fabric-wide buffer/block-size tuning pushed by the controller AutoTuner
                nettyServer.applyNetTuning(incoming.getParams());
                incoming.setParam("status", "10");
                return incoming;
            }
        } catch (Exception ex) {
            logger.error("executeCONFIG nettuning error: " + ex.getMessage());
        }
        return null;
    }
    @Override
    public MsgEvent executeDISCOVER(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeERROR(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeINFO(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeEXEC(MsgEvent ce) {

        switch (ce.getParam("action")) {

            case "globalinfo":
                return getGlobalInfo(ce);

            case "getmetrics":
                // B-2 unified metrics: wsapi dataplane connection/throughput gauges
                ce.setParam("metrics", getMetricsJson());
                ce.setParam("status", "10");
                return ce;

            default:
                logger.error("Unknown configtype found {} for {}:", ce.getParam("action"), ce.getMsgType().toString());

        }


        return null;
    }
    @Override
    public MsgEvent executeWATCHDOG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeKPI(MsgEvent incoming) {
        return null;
    }


    private MsgEvent getGlobalInfo(MsgEvent msg) {

        //msg.setCompressedParam("repolist",gson.toJson(repoMap));
        msg.setParam("global_region", plugin.getAgentService().getAgentState().getGlobalRegion());
        msg.setParam("global_agent", plugin.getAgentService().getAgentState().getGlobalAgent());
        return msg;

    }

}