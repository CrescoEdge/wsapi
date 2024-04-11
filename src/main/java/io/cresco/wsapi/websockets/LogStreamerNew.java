package io.cresco.wsapi.websockets;

import io.cresco.wsapi.Plugin;
import io.cresco.library.data.TopicType;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ClientEndpoint
@ServerEndpoint(value="/dashboard/logstream/{region}/{agent}")
public class LogStreamerNew
{
    private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
    private PluginBuilder plugin;
    private CLogger logger;

    public LogStreamerNew() {

        if(plugin == null) {
            if(Plugin.pluginBuilder != null) {
                plugin = Plugin.pluginBuilder;
                logger = plugin.getLogger(LogStreamerNew.class.getName(), CLogger.Level.Info);
            }
        }

    }

    @OnOpen
    public void onWebSocketConnect(Session sess)
    {
        sess.setMaxIdleTimeout(0);
        sessions.add(sess);
        //System.out.println("Socket Connected: " + sess);

        Map<String,String> pathparams = sess.getPathParameters();


        String region_id = pathparams.get("region");
        String agent_id = pathparams.get("agent");

        logger.debug("Socket Connected: " + sess + " region: " + region_id + " agent: " + agent_id);

        MessageListener ml = new MessageListener() {

            public void onMessage(Message msg) {
                try {
                    System.out.println("onMessage(Message msg) log streamer");
                    if (msg instanceof TextMessage) {

                        TextMessage textMessage = (TextMessage)msg;
                        //System.out.println("MESSAGE: [" + textMessage + "]");
                        /*
                        TextMessage textMessage = pluginBuilder.getAgentService().getDataPlaneService().createTextMessage();
                    textMessage.setStringProperty("event","logger");
                    textMessage.setStringProperty("pluginname",pluginBuilder.getConfig().getStringParam("pluginname"));
                    textMessage.setStringProperty("region_id",pluginBuilder.getRegion());
                    textMessage.setStringProperty("agent_id",pluginBuilder.getAgent());
                    textMessage.setStringProperty("plugin_id", pluginBuilder.getPluginID());
                    textMessage.setStringProperty("loglevel", loglevel);
                    textMessage.setText(message);
                         */


                        String messageString = textMessage.getStringProperty("region_id") + "_" + textMessage.getStringProperty("agent_id") + " [ " + textMessage.getStringProperty("logid") + "] " + textMessage.getStringProperty("loglevel") + " " + textMessage.getText();
                        sess.getAsyncRemote().sendObject(messageString);
                    }

                } catch(Exception ex) {

                    ex.printStackTrace();
                }
            }
        };

        String DPQuery = "region_id IS NOT NULL AND agent_id IS NOT NULL AND event = 'logger'";
        Plugin.pluginBuilder.getAgentService().getDataPlaneService().addMessageListener(TopicType.AGENT,ml,DPQuery);

    }

    @OnMessage
    public void onWebSocketText(Session sess, String message)
    {
        logger.info("Received TEXT message: " + message);

        String[] sst = message.split(",");
        if(sst.length == 4) {
            String region_id = sst[0];
            String agent_id = sst[1];
            String baseclass = sst[2];
            String loglevel = sst[3];

            if(!isAgentLogDP(region_id, agent_id)) {
                setAgentLogDP(region_id,agent_id,true);
            }

            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region_id, agent_id);
            req.setParam("action","setloglevel");
            req.setParam("baseclassname", baseclass);
            req.setParam("loglevel", loglevel);

            MsgEvent resp = plugin.sendRPC(req);
            String respMessage = "Error setting loglevel";
            if(resp != null) {
                if(resp.paramsContains("status_code")) {
                    if(resp.getParam("status_code").equals("7")) {
                        respMessage = "set loglevel: " + loglevel + " for baseclass: " + baseclass + " on region_id:" + region_id + " agent_id:" + agent_id;
                    } else {
                        if(resp.paramsContains("status_code")) {
                            respMessage = "could not set loglevel status_code: " + resp.getParam("status_code") + " status_desc: " + resp.getParam("status_desc");
                        } else {
                            respMessage = "could not set loglevel status_code: " + resp.getParam("status_code");
                        }
                    }
                }
            }
            sess.getAsyncRemote().sendObject(respMessage);

        }

    }

    @OnClose
    public void onWebSocketClose(Session sess, CloseReason reason)
    {
        logger.info("Socket Closed: " + reason);
        //System.out.println("Socket Closed: " + reason);
        sessions.remove(sess);
    }

    @OnError
    public void onWebSocketError(Throwable cause)
    {
        cause.printStackTrace(System.err);
    }

    private boolean isAgentLogDP(String region_id, String agent_id) {
        boolean isEnable = false;
        try {
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region_id, agent_id);
            req.setParam("action","getislogdp");
            MsgEvent resp = plugin.sendRPC(req);
            if(resp != null) {
                if(resp.paramsContains("islogdp")) {
                    isEnable = Boolean.parseBoolean(resp.getParam("islogdp"));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return isEnable;
    }

    private boolean setAgentLogDP(String region_id, String agent_id, boolean isEnabled) {
        boolean isEnable = false;
        try {
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region_id, agent_id);
            req.setParam("action","setlogdp");
            req.setParam("setlogdp",Boolean.TRUE.toString());

            MsgEvent resp = plugin.sendRPC(req);
            if(resp != null) {
                if(resp.paramsContains("status_code")) {
                    int statusCode = Integer.parseInt(resp.getParam("status_code"));
                    if(statusCode == 7) {
                        isEnable = true;
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return isEnable;
    }


    public void broadcast(String message) {

        synchronized (sessions) {
            sessions.forEach(session -> {
                if (session.isOpen()) {
                    session.getAsyncRemote().sendObject(message);
                }
            });
        }
    }
}