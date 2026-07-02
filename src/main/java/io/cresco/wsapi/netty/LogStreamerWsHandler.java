package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Netty port of {@code APILogStreamer}. On WS handshake complete: registers a JMS listener on the
 * AGENT topic filtered by this connection's session_id and pushes formatted log lines. Client text
 * frames are CSV {@code region,agent,loglevel,baseclass} config updates applied via a setloglevel RPC.
 */
public class LogStreamerWsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Gson gson = new Gson();

    private final PluginBuilder plugin;
    private final CLogger logger;
    private final String logSessionId = UUID.randomUUID().toString();
    private volatile String listenerId;

    public LogStreamerWsHandler(PluginBuilder plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(LogStreamerWsHandler.class.getName(), CLogger.Level.Info);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            Map<String, String> response = new HashMap<>();
            try {
                if (createListener(ctx.channel())) {
                    response.put("status_code", "10");
                    response.put("status_desc", "Listener Active");
                } else {
                    response.put("status_code", "9");
                    response.put("status_desc", "Could not activate listener");
                }
            } catch (Exception ex) {
                response.put("status_code", "90");
                response.put("status_desc", ex.getMessage());
            }
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
        }
        super.userEventTriggered(ctx, evt);
    }

    private boolean createListener(final Channel ch) {
        try {
            MessageListener ml = new MessageListener() {
                @Override
                public void onMessage(Message msg) {
                    try {
                        if (msg instanceof TextMessage) {
                            TextMessage tm = (TextMessage) msg;
                            String line = tm.getStringProperty("region_id") + "_" + tm.getStringProperty("agent_id")
                                    + " [ " + tm.getStringProperty("logid") + "] " + tm.getStringProperty("loglevel")
                                    + " " + tm.getText();
                            ch.writeAndFlush(new TextWebSocketFrame(line));
                        }
                    } catch (Exception ex) {
                        logger.error("LogStreamer listener.onMessage failed: " + ex.getMessage());
                    }
                }
            };
            String dpQuery = "region_id IS NOT NULL AND agent_id IS NOT NULL AND event = 'logger' AND session_id = '" + logSessionId + "'";
            listenerId = plugin.getAgentService().getDataPlaneService().addMessageListener(TopicType.AGENT, ml, dpQuery);
            return true;
        } catch (Exception ex) {
            logger.error("LogStreamer.createListener failed: " + ex.getMessage());
            return false;
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame)) return;
        String message = ((TextWebSocketFrame) frame).text();
        String[] sst = message.split(",");
        if (sst.length != 4) return;
        try {
            String regionId = sst[0], agentId = sst[1], loglevel = sst[2], baseclass = sst[3];
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, regionId, agentId);
            req.setParam("action", "setloglevel");
            req.setParam("baseclassname", baseclass);
            req.setParam("loglevel", loglevel);
            req.setParam("session_id", logSessionId);
            MsgEvent resp = plugin.sendRPC(req);
            String respMessage = "Error setting loglevel";
            if (resp != null && resp.paramsContains("status_code")) {
                if ("7".equals(resp.getParam("status_code"))) {
                    respMessage = "set loglevel: " + loglevel + " for baseclass: " + baseclass + " on region_id:" + regionId + " agent_id:" + agentId;
                } else {
                    respMessage = "could not set loglevel status_code: " + resp.getParam("status_code") + " status_desc: " + resp.getParam("status_desc");
                }
            }
            ctx.writeAndFlush(new TextWebSocketFrame(respMessage));
        } catch (Exception ex) {
            logger.error("LogStreamerWsHandler: " + ex.getMessage());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (listenerId != null) {
            try { plugin.getAgentService().getDataPlaneService().removeMessageListener(listenerId); } catch (Exception ignore) {}
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("LogStreamerWsHandler error: " + cause.getMessage());
        ctx.close();
    }
}
