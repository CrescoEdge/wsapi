package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.Authenticator;
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
    // Tracks which agent this session has DP log streaming enabled on. A session must be enabled
    // (setlogdp=true -> DataPlaneLogger.setIsEnabled) BEFORE setloglevel will take, and before any
    // log line is mirrored to the dataplane. The original APILogStreamer did this on config; the
    // netty port dropped it, so setloglevel returned status 9 and nothing ever streamed.
    private volatile boolean dpEnabled = false;
    private volatile String enabledRegion;
    private volatile String enabledAgent;

    private final Authenticator.Principal principal;
    private final WsRouter.Gate gate;

    public LogStreamerWsHandler(PluginBuilder plugin, Authenticator.Principal principal, WsRouter.Gate gate) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(LogStreamerWsHandler.class.getName(), CLogger.Level.Info);
        this.principal = principal;
        this.gate = gate;
    }

    /** The configuration RPCs this stream sends go out for its authenticated client, never as wsapi itself. */
    private MsgEvent rpc(MsgEvent req) {
        return gate.gateway.rpc(req, CONFIG_RPC_TIMEOUT_MS, principal);
    }

    /** How long one setlogdp / setloglevel RPC may wait for the named agent. */
    static final long CONFIG_RPC_TIMEOUT_MS = 10_000L;

    /**
     * Log-stream configuration runs here, never on a Netty event loop: its RPCs wait for the named agent (up to
     * {@link #CONFIG_RPC_TIMEOUT_MS}), and a line naming an agent that does not answer used to hold the connection's
     * event loop, and every other client on it, for the library's 30 s RPC timeout. Bounded: 8 threads, 64 queued;
     * beyond that a client is told to retry.
     */
    static final java.util.concurrent.ThreadPoolExecutor CONFIG_POOL = new java.util.concurrent.ThreadPoolExecutor(8, 8,
            60L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "wsapi-logstream-config");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    static {
        CONFIG_POOL.allowCoreThreadTimeOut(true);
    }

    /** One configuration at a time per log stream. */
    private final java.util.concurrent.atomic.AtomicBoolean configuring = new java.util.concurrent.atomic.AtomicBoolean();

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
                            // message parameters in the line are redacted by allowlist (LogRedaction)
                            String line = tm.getStringProperty("region_id") + "_" + tm.getStringProperty("agent_id")
                                    + " [ " + tm.getStringProperty("logid") + "] " + tm.getStringProperty("loglevel")
                                    + " " + LogRedaction.redact(tm.getText());
                            ch.writeAndFlush(new TextWebSocketFrame(line));
                        }
                    } catch (Exception ex) {
                        logger.error("LogStreamer listener.onMessage failed: " + ex.getMessage());
                    }
                }
            };
            String dpQuery = "region_id IS NOT NULL AND agent_id IS NOT NULL AND event = 'logger' AND session_id = '" + logSessionId + "'";
            // a user never receives lines below Warn (Info, Debug and Trace can carry message contents)
            if (!principal.isAdmin()) dpQuery += " AND (loglevel = 'Warn' OR loglevel = 'Error')";
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
        if (!principal.isAdmin()) {
            // log levels and log routing are an admin's: a user raising io.cresco to Trace read other users' messages
            logger.warn("LogStreamerWsHandler: refused a log configuration from " + principal + " (admin only)");
            ctx.writeAndFlush(new TextWebSocketFrame("refused: only an admin may set log levels or log routing"));
            return;
        }
        String message = ((TextWebSocketFrame) frame).text();
        String[] sst = message.split(",");
        if (sst.length != 4) return;
        Channel ch = ctx.channel();
        if (!configuring.compareAndSet(false, true)) {
            ch.writeAndFlush(new TextWebSocketFrame("busy: the previous log configuration is still being applied; retry shortly"));
            return;
        }
        try {
            CONFIG_POOL.execute(() -> {
                try {
                    String reply = configure(sst[0], sst[1], sst[2], sst[3]);
                    if (ch.isActive()) ch.writeAndFlush(new TextWebSocketFrame(reply));
                } catch (Exception ex) {
                    logger.error("LogStreamerWsHandler: " + ex.getMessage());
                } finally {
                    configuring.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            configuring.set(false);
            ch.writeAndFlush(new TextWebSocketFrame("busy: wsapi is applying other log configurations; retry shortly"));
        }
    }

    /** Point this session's log stream at an agent and set a level there (on {@link #CONFIG_POOL}); the reply line. */
    private String configure(String regionId, String agentId, String loglevel, String baseclass) {
        // Enable dataplane log streaming for this session on the target agent before setting the
        // level. If the client retargets a different agent on the same socket, disable the old one.
        if (!dpEnabled || !agentId.equals(enabledAgent) || !regionId.equals(enabledRegion)) {
            if (dpEnabled && enabledAgent != null) {
                setAgentLogDP(enabledRegion, enabledAgent, false);
                dpEnabled = false;
            }
            if (setAgentLogDP(regionId, agentId, true)) {
                dpEnabled = true;
                enabledRegion = regionId;
                enabledAgent = agentId;
            }
        }
        MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, regionId, agentId);
        req.setParam("action", "setloglevel");
        req.setParam("baseclassname", baseclass);
        req.setParam("loglevel", loglevel);
        req.setParam("session_id", logSessionId);
        MsgEvent resp = rpc(req);
        if (resp != null && resp.paramsContains("status_code")) {
            if ("7".equals(resp.getParam("status_code"))) {
                return "set loglevel: " + loglevel + " for baseclass: " + baseclass + " on region_id:" + regionId + " agent_id:" + agentId;
            }
            return "could not set loglevel status_code: " + resp.getParam("status_code") + " status_desc: " + resp.getParam("status_desc");
        }
        return "Error setting loglevel";
    }

    /**
     * Enable/disable dataplane log streaming for this socket's session on a target agent via the
     * agent {@code setlogdp} action (-> PluginAdmin.logDPSetEnabled -> DataPlaneLogger.setIsEnabled).
     * Returns true on success (status_code 7).
     */
    private boolean setAgentLogDP(String regionId, String agentId, boolean isEnabled) {
        try {
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, regionId, agentId);
            req.setParam("action", "setlogdp");
            req.setParam("setlogdp", String.valueOf(isEnabled));
            req.setParam("session_id", logSessionId);
            MsgEvent resp = rpc(req);
            return resp != null && resp.paramsContains("status_code") && "7".equals(resp.getParam("status_code"));
        } catch (Exception ex) {
            logger.error("LogStreamer.setAgentLogDP failed: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Disable DP streaming for this session so the agent stops mirroring logs once nobody listens: a one-way
        // message (nothing waits for the agent's answer), sent from the configuration pool, never this event loop.
        if (dpEnabled && enabledRegion != null) {
            final String region = enabledRegion, agent = enabledAgent;
            dpEnabled = false;
            try {
                CONFIG_POOL.execute(() -> {
                    try {
                        MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, region, agent);
                        req.setParam("action", "setlogdp");
                        req.setParam("setlogdp", "false");
                        req.setParam("session_id", logSessionId);
                        gate.gateway.send(req, principal);
                    } catch (Exception ex) {
                        logger.warn("LogStreamer: could not turn off log streaming on " + region + "/" + agent + ": " + ex.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                logger.warn("LogStreamer: configuration pool full; log streaming on " + region + "/" + agent
                        + " stays on for session " + logSessionId + " until that agent restarts");
            }
        }
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
