package io.cresco.wsapi.websockets;

import io.cresco.wsapi.Plugin;
import io.cresco.library.data.TopicType;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@ClientEndpoint
@ServerEndpoint(value="/dashboard/events/")
public class EventSocket
{
    private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());


    @OnOpen
    public void onWebSocketConnect(Session sess)
    {
        sess.setMaxIdleTimeout(0);
        sessions.add(sess);
        //System.out.println("Socket Connected: " + sess);

        MessageListener ml = new MessageListener() {

            public void onMessage(Message msg) {
                try {

                    if (msg instanceof TextMessage) {

                        TextMessage textMessage = (TextMessage)msg;
                        System.out.println("MESSAGE: [" + textMessage + "]");
                        sess.getAsyncRemote().sendObject(textMessage.getText());
                    }

                } catch(Exception ex) {

                    ex.printStackTrace();
                }
            }
        };

        Plugin.pluginBuilder.getAgentService().getDataPlaneService().addMessageListener(TopicType.AGENT,ml,"");

    }

    @OnMessage
    public void onWebSocketText(String message)
    {
        System.out.println("Received TEXT message: " + message);
        //broadcast(message);

        /*
        try {
            TextMessage textMessage = Plugin.pluginBuilder.getAgentService().getDataPlaneService().createTextMessage();
            textMessage.setText("FROM QUEUE: " + message);
            Plugin.pluginBuilder.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT, textMessage);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
         */
    }

    @OnClose
    public void onWebSocketClose(Session sess, CloseReason reason)
    {
        System.out.println("Socket Closed: " + reason);
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