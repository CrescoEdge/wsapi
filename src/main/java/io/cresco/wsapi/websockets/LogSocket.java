package io.cresco.wsapi.websockets;

import io.cresco.wsapi.Plugin;
import io.cresco.library.data.TopicType;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.util.*;

@ClientEndpoint
@ServerEndpoint(value="/dashboard/logsocket")
public class LogSocket
{
    private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String,SessionInfo> activeHost = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String,String> sessionMap = Collections.synchronizedMap(new HashMap<>());

    private PluginBuilder plugin;
    private CLogger logger;

    public LogSocket() {

        if(plugin == null) {
            if(Plugin.pluginBuilder != null) {
                plugin = Plugin.pluginBuilder;
                logger = plugin.getLogger(LogSocket.class.getName(), CLogger.Level.Info);
            }
        }

    }

    @OnOpen
    public void onWebSocketConnect(Session sess)
    {
        sess.setMaxIdleTimeout(0);
        sessions.add(sess);
        String logSessionId = UUID.randomUUID().toString();
        sessionMap.put(sess.getId(),logSessionId);
        //System.out.println("Socket Connected: " + sess);
        logger.debug("Socket Connected: " + sess.getId());

        MessageListener ml = new MessageListener() {

            public void onMessage(Message msg) {
                try {
                    System.out.println("onMessage(Message msg) log socket" );
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

        String DPQuery = "region_id IS NOT NULL AND agent_id IS NOT NULL AND event = 'logger' AND session_id = '" + logSessionId + "'";
        //String DPQuery = "region_id IS NOT NULL AND agent_id IS NOT NULL AND event = 'logger'";
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

            if(!isAgentLogDP(sess.getId(), region_id, agent_id)) {
                setAgentLogDP(sess.getId(), region_id,agent_id,true);
            }

            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region_id, agent_id);
            req.setParam("action","setloglevel");
            req.setParam("baseclassname", baseclass);
            req.setParam("loglevel", loglevel);
            req.setParam("session_id", sessionMap.get(sess.getId()));

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

        if(activeHost.containsKey(sess.getId())) {
            SessionInfo sessionInfo = activeHost.get(sess.getId());
            if(isAgentLogDP(sessionInfo.logSessionId, sessionInfo.regionId, sessionInfo.agentId)) {
                setAgentLogDP(sessionInfo.logSessionId, sessionInfo.regionId, sessionInfo.agentId,false);
                logger.error("removing sessionId: " + sessionInfo.logSessionId + " from regionId: " + sessionInfo.regionId + " agentId: " + sessionInfo.agentId);
            }
        }

        sessions.remove(sess);
    }

    @OnError
    public void onWebSocketError(Throwable cause)
    {
        cause.printStackTrace(System.err);
    }

    private boolean isAgentLogDP(String sessionId, String region_id, String agent_id) {
        boolean isEnable = false;
        try {
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region_id, agent_id);
            req.setParam("action","getislogdp");
            req.setParam("session_id", sessionMap.get(sessionId));
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

    private boolean setAgentLogDP(String sessionId, String region_id, String agent_id, boolean isEnabled) {
        boolean isSet = false;
        try {
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region_id, agent_id);
            req.setParam("action","setlogdp");
            req.setParam("setlogdp",String.valueOf(isEnabled));
            req.setParam("session_id", sessionMap.get(sessionId));

            MsgEvent resp = plugin.sendRPC(req);
            if(resp != null) {
                if(resp.paramsContains("status_code")) {
                    int statusCode = Integer.parseInt(resp.getParam("status_code"));
                    if(statusCode == 7) {
                        if(isEnabled) {
                            String logSessionId = sessionMap.get(sessionId);
                            activeHost.put(sessionId,new SessionInfo(logSessionId,sessionId,region_id,agent_id));
                        } else {
                            activeHost.remove(sessionId);
                        }
                        isSet = true;
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return isSet;
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