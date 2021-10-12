package io.cresco.wsapi.websockets;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.Plugin;

import javax.jms.Message;
import javax.jms.TextMessage;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.lang.reflect.Type;
import java.util.*;

@ClientEndpoint
@ServerEndpoint(value="/api/dataplane")
public class APIDataPlane
{
    private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String,SessionInfo> activeHost = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String,String> sessionMap = Collections.synchronizedMap(new HashMap<>());
    private static final Type type = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
    private static final Gson gson = new Gson();


    private PluginBuilder plugin;
    private CLogger logger;

    public APIDataPlane() {

        if(plugin == null) {
            if(Plugin.pluginBuilder != null) {
                plugin = Plugin.pluginBuilder;
                logger = plugin.getLogger(APIDataPlane.class.getName(), CLogger.Level.Info);
            }
        }

    }

    @OnOpen
    public void onWebSocketConnect(Session sess)
    {
        sess.setMaxBinaryMessageBufferSize(50000000);
        sess.setMaxTextMessageBufferSize(50000000);
        sessions.add(sess);
        //String logSessionId = UUID.randomUUID().toString();
        //System.out.println("Socket Connected: " + sess);
        //logger.info("Socket Connected: " + sess.getId());

    }

    @OnMessage
    public void onWebSocketText(Session sess, String message)
    {
        Map<String, String> responce = new HashMap<>();
        responce.put("stream_query",message);
        try {

            if (createListener(sess, message)) {
                responce.put("status_code", "10");
                responce.put("status_desc", "Listener Active");

            } else {
                responce.put("status_code", "9");
                responce.put("status_desc", "Could not activate listener");

            }

        } catch (Exception ex) {
            ex.printStackTrace();
            responce.put("status_code", "90");
            responce.put("status_desc", ex.getMessage());
            ex.printStackTrace();
        }

        sess.getAsyncRemote().sendObject(gson.toJson(responce));

    }

    private boolean createListener(Session sess, String stream_query) {
        boolean isCreated = false;
        try{

            javax.jms.MessageListener ml = new javax.jms.MessageListener() {
                public void onMessage(Message msg) {
                    try {


                        if (msg instanceof TextMessage) {

                            sess.getAsyncRemote().sendObject(((TextMessage) msg).getText());

                        }
                    } catch(Exception ex) {

                        ex.printStackTrace();
                    }
                }
            };
            //logger.error("APIDataPlane: creating listener: " + "stream_query=" + stream_query + "");
            String listenerid = plugin.getAgentService().getDataPlaneService().addMessageListener(TopicType.AGENT,ml,stream_query);
            sessionMap.put(sess.getId(),listenerid);
            //sess.getAsyncRemote().sendObject("APIDataPlane Connected Session: " + sess.getId());

            isCreated = true;

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return isCreated;
    }

    @OnClose
    public void onWebSocketClose(Session sess, CloseReason reason)
    {
        //logger.info("Socket Closed: " + reason);
        //System.out.println("Socket Closed: " + reason);

        String listenerid = sessionMap.get(sess.getId());
        //so we don't get messages about disabling logger
        Plugin.pluginBuilder.getAgentService().getDataPlaneService().removeMessageListener(listenerid);

        if(activeHost.containsKey(sess.getId())) {
            SessionInfo sessionInfo = activeHost.get(sess.getId());
            logger.error("removing sessionId: " + sessionInfo.logSessionId + " from regionId: " + sessionInfo.regionId + " agentId: " + sessionInfo.agentId);
        }

        sessions.remove(sess);
    }

    @OnError
    public void onWebSocketError(Throwable cause)
    {
        cause.printStackTrace(System.err);
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