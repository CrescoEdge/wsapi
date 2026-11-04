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

    /** This server's log-stream pools (configuration and undo; {@link LogStreamPools}). */
    private LogStreamPools pools() {
        return gate.logStreams;
    }

    /** One configuration at a time per log stream. */
    private final java.util.concurrent.atomic.AtomicBoolean configuring = new java.util.concurrent.atomic.AtomicBoolean();
    /** Configuration tasks an admin may hold on the pool at a time, across its streams (a fair share of it). */
    static final int POOL_SHARE_PER_ADMIN = 2;
    /** The stream closed: a configuration in flight stops at its next step, and logging is turned off again. */
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean undone = new java.util.concurrent.atomic.AtomicBoolean();
    /** Agents ("region/agent") this stream sent setlogdp=true to and has not turned off since, answered or not. */
    private final java.util.Set<String> touched = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        // a credential revoked or changed since the upgrade: no configuration, the connection is being closed
        if (gate != null && !gate.admit(ctx.channel(), principal)) return;
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
        java.util.concurrent.atomic.AtomicInteger mine = pools().inPool.computeIfAbsent(principal.key(), k -> new java.util.concurrent.atomic.AtomicInteger());
        if (mine.incrementAndGet() > POOL_SHARE_PER_ADMIN) {
            mine.decrementAndGet();
            configuring.set(false);
            ch.writeAndFlush(new TextWebSocketFrame("busy: your other log streams are still being configured; retry shortly"));
            return;
        }
        try {
            pools().config.execute(() -> {
                try {
                    String reply = configure(sst[0], sst[1], sst[2], sst[3]);
                    if (ch.isActive()) ch.writeAndFlush(new TextWebSocketFrame(reply));
                } catch (Exception ex) {
                    logger.error("LogStreamerWsHandler: " + ex.getMessage());
                } finally {
                    mine.decrementAndGet();
                    configuring.set(false);
                    if (closed) undoOnce();                 // closed meanwhile: undo what this configuration started
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            mine.decrementAndGet();
            configuring.set(false);
            ch.writeAndFlush(new TextWebSocketFrame("busy: wsapi is applying other log configurations; retry shortly"));
            if (closed) undoOnce();
        }
    }

    /** Turn log streaming off on every agent this stream turned it on at, once (on {@link LogStreamPools#undo}; never on an event loop). */
    private void undoOnce() {
        if (!undone.compareAndSet(false, true)) return;
        dpEnabled = false;
        if (touched.isEmpty()) return;
        Runnable undo = () -> {
            for (String t : new java.util.ArrayList<>(touched)) {
                int slash = t.indexOf('/');
                setAgentLogDP(t.substring(0, slash), t.substring(slash + 1), false);
            }
        };
        try {
            pools().undo.execute(undo);
        } catch (java.util.concurrent.RejectedExecutionException full) {
            pools().undoDropped.incrementAndGet();
            logger.error("LogStreamerWsHandler: " + LogStreamPools.UNDO_QUEUE + " closed log streams are already waiting to turn log streaming "
                    + "off; it stays on, mirroring to nobody, at " + touched + " (session " + logSessionId + ") until a log stream "
                    + "turns it off there or the agent restarts");
        }
    }

    /** Point this session's log stream at an agent and set a level there (on {@link LogStreamPools#config}); the reply line. */
    private String configure(String regionId, String agentId, String loglevel, String baseclass) {
        if (closed) return "closed";
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
        if (closed) return "closed";                         // the client left meanwhile: no level to set (undone in finally)
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
        String t = regionId + "/" + agentId;
        if (isEnabled) touched.add(t);                       // before it is sent: the agent may act on it unanswered
        try {
            MsgEvent req = plugin.getGlobalAgentMsgEvent(MsgEvent.Type.CONFIG, regionId, agentId);
            req.setParam("action", "setlogdp");
            req.setParam("setlogdp", String.valueOf(isEnabled));
            req.setParam("session_id", logSessionId);
            MsgEvent resp = rpc(req);
            boolean ok = resp != null && resp.paramsContains("status_code") && "7".equals(resp.getParam("status_code"));
            if (!isEnabled && ok) touched.remove(t);
            return ok;
        } catch (Exception ex) {
            logger.error("LogStreamer.setAgentLogDP failed: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Turn log streaming off again wherever this stream turned it on, also when a configuration is still in flight
        // (it stops at its next step and undoes in its finally) or was refused: never an agent mirroring to nobody.
        closed = true;
        if (!configuring.get()) undoOnce();
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
