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
        String logSessionId = UUID.randomUUID().toString();
        sessionMap.put(sess.getId(),logSessionId);
        //System.out.println("Socket Connected: " + sess);
        logger.info("Socket Connected: " + sess.getId());

    }

    @OnMessage
    public void onWebSocketText(Session sess, String message)
    {
        String r;

        logger.error("message: " + message);

        createListener(sess, message);

        //Map<String, Map<String, String>> incoming_message = gson.fromJson(message, type);

        //MsgEvent request = GetResponceMsgEvent(incoming_message);

        //boolean isRPC  = Boolean.valueOf(incoming_message.get("message_info").get("is_rpc"));
        /*
        MsgEvent response = null;
        if(isRPC) {
            response = plugin.sendRPC(request);
            if (response == null)
                r = "{\"error\":\"Cresco rpc response was null\"}";
            else {
                r = gson.toJson(response.getParams());
            }

            sess.getAsyncRemote().sendObject(r);
        } else {
            plugin.msgOut(request);
        }

         */

    }

    private void createListener(Session sess, String stream_name) {

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

            plugin.getAgentService().getDataPlaneService().addMessageListener(TopicType.AGENT,ml,"stream_name='" + stream_name + "'");


        } catch (Exception ex) {
            ex.printStackTrace();
        }


    }

    private MsgEvent GetResponceMsgEvent(Map<String, Map<String, String>> incoming_message) {
        MsgEvent request = null;
        try {

            Map<String,String> messageInfo = incoming_message.get("message_info");
            Map<String,String> messagePayload = incoming_message.get("message_payload");

            String messageType = messageInfo.get("message_type");

            switch (messageType) {

                case "global_controller_msgevent":
                    //request = GlobalControllerMsgEvent(messageInfo);
                    break;


                default:
                    logger.error("Unknown message type");

            }

            for (Map.Entry<String, String> entry : messagePayload.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                request.setParam(key, value);
            }


        } catch (Exception ex) {
            logger.error(ex.getMessage());
        }
        return request;
    }


    @OnClose
    public void onWebSocketClose(Session sess, CloseReason reason)
    {
        logger.info("Socket Closed: " + reason);
        //System.out.println("Socket Closed: " + reason);

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