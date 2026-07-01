package io.cresco.wsapi.websockets;


import com.google.common.primitives.Bytes;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.Plugin;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;


import jakarta.websocket.*;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnOpen;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.websocket.core.CoreSession;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;



@ClientEndpoint
@ServerEndpoint(value="/api/dataplane")
public class APIDataPlane
{
    private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String,StreamInfo> sessionMap = Collections.synchronizedMap(new HashMap<>());
    //private static final Map<String,String> activeHost = Collections.synchronizedMap(new HashMap<>());
    //private static final Type type = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
    private static final Type hashtype = new TypeToken<Map<String, String>>(){}.getType();
    private static final Gson gson = new Gson();
    private AtomicBoolean lockSessions = new AtomicBoolean();
    private AtomicBoolean lockSessionMap = new AtomicBoolean();
    //synchronized (lockConfig) {

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
        sess.setMaxIdleTimeout(0);
        sess.setMaxBinaryMessageBufferSize(1024 * 1024 * 1024);
        sess.setMaxTextMessageBufferSize(1024 * 1024 * 1024);

        // NOTE: CoreSession.setInputBufferSize() is intentionally NOT called. On Jetty 12.0.17 it
        // corrupts the leading 16 bytes of any binary message spanning more than one TLS record
        // (proven with a raw Python sender: server receives an all-zero header, byte-for-byte),
        // even when matched with maxFrameSize/maxBinaryMessageSize on both peers — AND it lowers
        // throughput. The default read buffer is byte-exact and, with async send + no
        // permessage-deflate, already matches the pre-Jetty-12 large-message throughput.

        synchronized (lockSessions) {
            sessions.add(sess);
        }
        //String logSessionId = UUID.randomUUID().toString();
        //System.out.println("Socket Connected: " + sess);
        //logger.info("Socket Connected: " + sess.getId());

    }

    private boolean isActive(Session sess) {
        boolean isActive = false;
        try {

            synchronized (lockSessionMap) {
                if(sessionMap.containsKey(sess.getId())) {
                    isActive = true;
                }
            }

        } catch (Exception ex) {
            logger.error("isActive() " + ex.getMessage());
        }

        return isActive;
    }

    @OnMessage
    public void onWebSocketText(Session sess, String message)
    {
        if(isActive(sess)) {

            try {

                String identKey;
                String identId;
                String ioTypeKey;
                String inputId;

                synchronized (lockSessionMap) {
                    identKey = sessionMap.get(sess.getId()).getIdentKey();
                    identId = sessionMap.get(sess.getId()).getIdentId();
                    ioTypeKey = sessionMap.get(sess.getId()).getIoTypeKey();
                    inputId = sessionMap.get(sess.getId()).getInputId();
                }

                if((identKey != null) && (identId != null)) {
                    TextMessage updateMessage = plugin.getAgentService().getDataPlaneService().createTextMessage();
                    updateMessage.setText(message);
                    updateMessage.setStringProperty(identKey, identId);
                    updateMessage.setStringProperty(ioTypeKey, inputId);

                    plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.GLOBAL, updateMessage);
                } else {
                    logger.error("identKey and identId are null for session_id: " + sess.getId());
                }
            } catch (Exception ex) {
                logger.error("onWebSocketText: " + ex.getMessage());
            }

        } else {

            Map<String,String> mapMessage = null;
            try {
                mapMessage = gson.fromJson(message,hashtype);
            } catch (Exception ex) {
                //fail silent
            }

            StreamInfo streamInfo = null;
            if(mapMessage != null) {
                streamInfo = new StreamInfo(sess.getId(),mapMessage.get("ident_key"), mapMessage.get("ident_id"));
                streamInfo.setIoTypeKey(mapMessage.get("io_type_key"));
                streamInfo.setOutputId(mapMessage.get("output_id"));
                streamInfo.setInputId(mapMessage.get("input_id"));

            } else {
                streamInfo = new StreamInfo(sess.getId(),message);
            }

            Map<String, String> responce = new HashMap<>();
            //responce.put("stream_query", message);
            try {

                if (createListener(sess, streamInfo)) {
                    responce.put("status_code", "10");
                    responce.put("status_desc", "Listener Active");

                } else {
                    responce.put("status_code", "9");
                    responce.put("status_desc", "Could not activate listener");

                }

            } catch (Exception ex) {
                logger.error("onWebSocketText() createListener failed", ex);
                responce.put("status_code", "90");
                responce.put("status_desc", ex.getMessage());
            }

            sess.getAsyncRemote().sendObject(gson.toJson(responce));

        }

    }

    // Whole-message binary handler. The (byte[], boolean last) signature would opt into
    // jakarta.websocket PARTIAL delivery, which Jetty 12 honors by handing us input-buffer
    // -sized fragments (~8KB) of a large message — each of which we would forward to the
    // broker as its own BytesMessage, shattering the caller's message boundaries (and the
    // embedded seq_num/timestamp header lives only in fragment 0). Large transfers are
    // already chunked at the application layer via seq_num, so each websocket message must
    // be delivered whole. onOpen sets maxBinaryMessageBufferSize=1GB to allow that.
    @OnMessage
    public void processUpload(byte[] b, Session sess) {

        if(isActive(sess)) {

            try {

                String identKey;
                String identId;
                String ioTypeKey;
                String inputId;

                synchronized (lockSessionMap) {
                    identKey = sessionMap.get(sess.getId()).getIdentKey();
                    identId = sessionMap.get(sess.getId()).getIdentId();
                    ioTypeKey = sessionMap.get(sess.getId()).getIoTypeKey();
                    inputId = sessionMap.get(sess.getId()).getInputId();
                }

                if((identKey != null) && (identId != null)) {

                    BytesMessage updateMessage = plugin.getAgentService().getDataPlaneService().createBytesMessage();
                    updateMessage.writeBytes(b);
                    updateMessage.setStringProperty(identKey, identId);
                    updateMessage.setStringProperty(ioTypeKey, inputId);

                    plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.GLOBAL, updateMessage);

                } else {
                    logger.error("identKey and identId are null for session_id: " + sess.getId());
                }
            } catch (Exception ex) {
                logger.error("processUpload: " + ex.getMessage());
            }

        }

    }

    private boolean createListener(Session sess, StreamInfo streamInfo) {
        boolean isCreated = false;
        try{

            MessageListener ml = new MessageListener() {
                public void onMessage(Message msg) {
                    try {
                        //System.out.println("onMessage(Message msg)  dataplane");
                        //logger.error("WHY ME");
                        if (msg instanceof TextMessage) {
                            sess.getAsyncRemote().sendText(((TextMessage) msg).getText());

                        } else if (msg instanceof BytesMessage) {
                            String transferId = msg.getStringProperty("transfer_id");


                            String seqNum = String.format("%1$" + 6 + "s", msg.getStringProperty("seq_num")).replace(' ', '0');
                            //logger.error("INCOMING TRANSFER ID: " + transferId + " seq: " + msg.getStringProperty("seq_num"));
                            //System.out.println("onMessage(Message msg) transferId: " + transferId);
                            long dataSize = ((BytesMessage) msg).getBodyLength();
                            byte[] bytes = new byte[(int)dataSize];
                            ((BytesMessage) msg).readBytes(bytes);
                            if(msg.getStringProperty("seq_num") != null) {
                                bytes = Bytes.concat(seqNum.getBytes(), bytes);
                            }
                            if(transferId != null) {
                                bytes = Bytes.concat(transferId.getBytes(), bytes);
                            }
                            ByteBuffer buffer = ByteBuffer.wrap(bytes);
                            sess.getAsyncRemote().sendBinary(buffer);
                        }

                    } catch(Exception ex) {

                        logger.error("MessageListener.onMessage() failed", ex);
                    }
                }
            };

            //logger.error("APIDataPlane: creating listener: " + "stream_query=" + stream_query + "");
            String stream_query;

            if(streamInfo.getStream_query() != null) {
                stream_query = streamInfo.getStream_query();
            } else {
                //stream_query = streamInfo.getIdentKey() + "='" + streamInfo.getIdentId() + "' and " + streamInfo.getIoTypeKey() + "='" + streamInfo.getOutputId() + "'";
                stream_query = streamInfo.getIdentKey() + "='" + streamInfo.getIdentId() + "'";

            }
            //logger.info("stream_query: " + stream_query);

            String listenerid = plugin.getAgentService().getDataPlaneService().addMessageListener(TopicType.GLOBAL,ml,stream_query);

            streamInfo.setListenerId(listenerid);

            synchronized (lockSessionMap) {
                sessionMap.put(sess.getId(), streamInfo);
            }
            //System.out.println("createListner() sessionId: " + sess.getId() + " listenerid: " + listenerid);

            //sess.getAsyncRemote().sendObject("APIDataPlane Connected Session: " + sess.getId());

            isCreated = true;

        } catch (Exception ex) {
            logger.error("createListener() failed", ex);
        }

        return isCreated;
    }

    @OnClose
    public void onWebSocketClose(Session sess, CloseReason reason)
    {
        //logger.info("Socket Closed: " + reason);
        //System.out.println("Socket Closed: " + reason);
        String listenerid = null;
        synchronized (lockSessionMap) {
            listenerid = sessionMap.get(sess.getId()).getListenerId();

        }
        logger.info("closeListner() sessionId: " + sess.getId() + " listenerid: " + listenerid);

        //so we don't get messages about disabling logger
        if (listenerid != null) {
            Plugin.pluginBuilder.getAgentService().getDataPlaneService().removeMessageListener(listenerid);
        } else {
            logger.error("onWebSocketClose(): sessionMap = null : closed: " + reason.getReasonPhrase());
        }

        /*
        if(activeHost.containsKey(sess.getId())) {
            SessionInfo sessionInfo = activeHost.get(sess.getId());
            logger.error("removing sessionId: " + sessionInfo.logSessionId + " from regionId: " + sessionInfo.regionId + " agentId: " + sessionInfo.agentId);
        }

         */
        synchronized (lockSessions) {
            sessions.remove(sess);
        }
    }

    @OnError
    public void onWebSocketError(Throwable cause)
    {
        logger.error("websocket error", cause);
    }


    public void broadcast(String message) {

        synchronized (sessions) {
            synchronized (lockSessions) {
                sessions.forEach(session -> {
                    if (session.isOpen()) {
                        session.getAsyncRemote().sendObject(message);
                    }
                });
            }
        }
    }
}