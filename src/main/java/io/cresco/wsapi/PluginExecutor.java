package io.cresco.wsapi;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

public class PluginExecutor implements Executor {

    private PluginBuilder plugin;
    CLogger logger;

    public PluginExecutor(PluginBuilder pluginBuilder) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(PluginExecutor.class.getName(),CLogger.Level.Info);
    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {
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