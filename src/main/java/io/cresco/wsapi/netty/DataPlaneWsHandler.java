package io.cresco.wsapi.netty;

import com.google.common.primitives.Bytes;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.websockets.StreamInfo;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Netty port of {@code APIDataPlane}. First text frame is the stream handshake (JSON stream config
 * or a raw stream_query); it registers a JMS MessageListener on the dataplane topic and replies
 * with a status. Subsequent text/binary frames are published to the broker; broker messages are
 * forwarded back to this channel (with the seq_num/transfer_id egress framing preserved).
 */
public class DataPlaneWsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Gson gson = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    // B-2 unified metrics: process-wide dataplane counters exposed via the wsapi MeasurementEngine
    // (getmetrics). One wsapi plugin per JVM, so static aggregation across all handler instances is correct.
    public static final java.util.concurrent.atomic.AtomicInteger ACTIVE_CONNECTIONS = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicLong DATAPLANE_BYTES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DATAPLANE_MESSAGES = new java.util.concurrent.atomic.AtomicLong();
    // Egress accounting (wsapi getmetrics -> wsapi.dataplane.egress.*): frames dropped because a consumer
    // exceeded its backlog budget, bytes queued to consumers now, and the largest backlog one consumer reached.
    public static final java.util.concurrent.atomic.AtomicLong EGRESS_DROPPED = EgressBudget.DROPPED;
    public static final java.util.concurrent.atomic.AtomicLong EGRESS_PENDING_BYTES = EgressBudget.GLOBAL_PENDING;
    public static final java.util.concurrent.atomic.AtomicLong EGRESS_MAX_PENDING_BYTES = EgressBudget.MAX_PENDING;

    private final PluginBuilder plugin;
    private final CLogger logger;

    private volatile boolean active = false;
    private volatile String listenerId;
    private volatile StreamInfo streamInfo;
    private volatile int shard = 0;
    // frames dropped in the current overload episode (reset, with a summary line, when the consumer drains)
    private final java.util.concurrent.atomic.AtomicLong droppedFrames =
            new java.util.concurrent.atomic.AtomicLong();
    private Channel channel;
    // bounded egress backlog: frames queue past Netty's write high-water mark up to this consumer's byte
    // budget; only a consumer beyond it (overloaded) has frames dropped, each counted and logged
    private final EgressBudget egress;
    private final long egressCap;

    private final Authenticator.Principal principal;
    private final WsRouter.Gate gate;

    public DataPlaneWsHandler(PluginBuilder plugin, Authenticator.Principal principal, WsRouter.Gate gate) {
        this.plugin = plugin;
        this.principal = principal;
        this.gate = gate;
        this.logger = plugin.getLogger(DataPlaneWsHandler.class.getName(), CLogger.Level.Info);
        this.egressCap = plugin.getConfig().getLongParam("wsapi_dataplane_egress_buffer_bytes", 32L * 1024 * 1024);
        this.egress = new EgressBudget(egressCap,
                plugin.getConfig().getLongParam("wsapi_dataplane_egress_global_bytes", EgressBudget.defaultGlobalCap()));
    }

    /** Queues a frame to the consumer within its backlog budget; drops (counted, logged) past it. */
    private void deliver(Channel ch, WebSocketFrame frame) {
        final long size = frame.content().readableBytes();
        if (!ch.isActive()) {                 // consumer gone; its listener is being removed
            frame.release();
            return;
        }
        if (!egress.tryAcquire(size)) {
            frame.release();
            long dropped = droppedFrames.incrementAndGet();
            EGRESS_DROPPED.incrementAndGet();
            if (dropped == 1L || dropped % 1000L == 0L) {
                logger.warn("DataPlaneWsHandler: WS consumer overloaded: " + egress.pending() + " B queued (budget "
                        + egressCap + " B), dropped " + dropped + " frame(s) (wsapi.dataplane.egress.dropped total="
                        + EGRESS_DROPPED.get() + ")");
            }
            return;
        }
        ch.writeAndFlush(frame).addListener(f -> egress.release(size));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.channel = ctx.channel();
        ACTIVE_CONNECTIONS.incrementAndGet();
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        try {
            if (frame instanceof TextWebSocketFrame) {
                String message = ((TextWebSocketFrame) frame).text();
                if (!active) {
                    handshake(ctx, message);
                } else {
                    // data text -> broker. Bytes, not characters: the frame's UTF-8 payload (a multi-byte character
                    // counted 1 where it carries 2-4 bytes, so wsapi.dataplane.bytes under-reported text traffic)
                    DATAPLANE_BYTES.addAndGet(utf8Bytes((TextWebSocketFrame) frame));
                    DATAPLANE_MESSAGES.incrementAndGet();
                    TextMessage tm = plugin.getAgentService().getDataPlaneService().createTextMessage();
                    tm.setText(message);
                    tm.setStringProperty(streamInfo.getIdentKey(), streamInfo.getIdentId());
                    tm.setStringProperty(streamInfo.getIoTypeKey(), streamInfo.getInputId());
                    stampWriter(tm);
                    plugin.getAgentService().getDataPlaneService().sendMessage(
                            TopicType.GLOBAL, tm, jakarta.jms.DeliveryMode.NON_PERSISTENT, 0, 0, shard);
                }
            } else if (frame instanceof BinaryWebSocketFrame) {
                if (!active) return; // ignore data before handshake
                ByteBuf buf = frame.content();
                byte[] b = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), b);
                BytesMessage bm = plugin.getAgentService().getDataPlaneService().createBytesMessage();
                bm.writeBytes(b);
                bm.setStringProperty(streamInfo.getIdentKey(), streamInfo.getIdentId());
                bm.setStringProperty(streamInfo.getIoTypeKey(), streamInfo.getInputId());
                stampWriter(bm);
                bm.setIntProperty("dp_bytes", b.length); // readable payload size for link throughput metrics
                DATAPLANE_BYTES.addAndGet(b.length);
                DATAPLANE_MESSAGES.incrementAndGet();
                plugin.getAgentService().getDataPlaneService().sendMessage(
                        TopicType.GLOBAL, bm, jakarta.jms.DeliveryMode.NON_PERSISTENT, 0, 0, shard);
            }
        } catch (Exception ex) {
            logger.error("DataPlaneWsHandler.channelRead0: " + ex.getMessage());
        }
    }

    /** The verified writer of everything this client publishes: {@code cresco_writer} and {@code cresco_writer_roles}. */
    static final String WRITER = "cresco_writer";
    static final String WRITER_ROLES = "cresco_writer_roles";

    private void stampWriter(jakarta.jms.Message m) throws jakarta.jms.JMSException {
        if (principal == null) return;
        m.setStringProperty(WRITER, principal.caller());
        m.setStringProperty(WRITER_ROLES, principal.role);
    }

    /** The size of a text frame's payload in bytes (its UTF-8 encoding), not in characters. */
    static long utf8Bytes(TextWebSocketFrame frame) {
        return frame.content().readableBytes();
    }

    /** Handshakes a connection may have refused before it is closed. */
    static final int MAX_REFUSED_HANDSHAKES = 3;
    private int refusedHandshakes;

    /**
     * Refuse a handshake: status 9 with the reason. The stream stays unactivated and the next text frame is a new
     * handshake; after {@link #MAX_REFUSED_HANDSHAKES} refusals the connection is closed.
     */
    private void refuse(ChannelHandlerContext ctx, String why) {
        logger.warn("DataPlaneWsHandler: handshake refused for " + principal + ": " + why);
        Map<String, String> refused = new java.util.HashMap<>();
        refused.put("status_code", "9");
        refused.put("status_desc", "refused: " + why);
        io.netty.channel.ChannelFuture f = ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(refused)));
        if (++refusedHandshakes >= MAX_REFUSED_HANDSHAKES) f.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    /** The prefix of the dataplane streams a user owns: {@code u:<user>:}. */
    static String ownPrefix(Authenticator.Principal p) {
        return "u:" + p.user + ":";
    }

    /** May {@code p} use stream {@code name}: its own ({@code u:<user>:...}) or granted (exact, or a prefix ending in *)? */
    static boolean owns(Authenticator.Principal p, String name) {
        if (name == null) return false;
        if (name.startsWith(ownPrefix(p))) return true;
        for (String g : p.grants) {
            if (g.endsWith("*") ? name.startsWith(g.substring(0, g.length() - 1)) : name.equals(g)) return true;
        }
        return false;
    }

    /**
     * What a {@code user} may do on the dataplane (admins and the service key are not limited here): only streams it owns
     * ({@code u:<user>:...}) or was granted, named by {@code stream_name}; a raw subscription must anchor
     * {@code stream_name = '<such a stream>'}; it writes {@code type=output} only on its own streams (on a granted stream
     * that would pose as a process's output). Nobody writes stdin on the dataplane ({@link #stdinRefusal}).
     *
     * @return null when allowed, else why not
     */
    static String userStreamRefusal(StreamInfo si, Authenticator.Principal p) {
        if (p == null) return "no authenticated caller";
        if (p.isAdmin()) return null;
        if (si.getStream_query() != null) {
            List<String> anchors;
            try {
                anchors = StreamSelectors.equalityValues(si.getStream_query(), "stream_name");
            } catch (IllegalArgumentException bad) {
                return bad.getMessage();
            }
            for (String v : anchors) if (owns(p, v)) return null;
            return p + " may subscribe only to its own streams (stream_name = '" + ownPrefix(p) + "...') or streams granted to it";
        }
        if (!"stream_name".equals(si.getIdentKey())) return p + " names its streams with ident_key stream_name";
        if (!owns(p, si.getIdentId())) {
            return p + " may use only its own streams (" + ownPrefix(p) + "...) or streams granted to it, not " + si.getIdentId();
        }
        if ("type".equals(si.getIoTypeKey()) && "output".equals(si.getInputId()) && !si.getIdentId().startsWith(ownPrefix(p))) {
            return p + " may write type=output only on its own streams";
        }
        return null;
    }

    /**
     * Nobody writes a process's stdin on the dataplane ({@code type=input} or {@code input-*}), an admin included: the
     * executor takes stdin only from its {@code write_stdin} action (a MsgEvent the caller's identity travels with) and
     * reads none from the dataplane, so such frames would vanish. Refused with that reason instead.
     *
     * @return null when the stream writes no stdin, else why it is refused
     */
    static String stdinRefusal(StreamInfo si) {
        if (si.getIdentKey() == null || !"type".equals(si.getIoTypeKey()) || si.getInputId() == null) return null;
        String t = si.getInputId();
        if (!t.equals("input") && !t.startsWith("input-")) return null;
        return "a process's stdin (type=" + t + ") is not written on the dataplane: use the executor's write_stdin action";
    }

    private void handshake(ChannelHandlerContext ctx, String message) {
        // the dataplane wsapi reads and writes is this agent's tenant's: only callers of that tenant may use it
        String tenantRefusal = tenantRefusal(principal, gate == null ? null : gate.authenticator.agentTenant());
        if (tenantRefusal != null) {
            refuse(ctx, tenantRefusal);
            return;
        }
        StreamInfo si;
        Map<String, String> mapMessage = null;
        try { mapMessage = gson.fromJson(message, MAP_TYPE); } catch (Exception ignore) {}
        if (mapMessage != null && mapMessage.get("ident_key") != null) {
            si = new StreamInfo("netty", mapMessage.get("ident_key"), mapMessage.get("ident_id"));
            si.setIoTypeKey(mapMessage.get("io_type_key"));
            si.setOutputId(mapMessage.get("output_id"));
            si.setInputId(mapMessage.get("input_id"));
        } else {
            si = new StreamInfo("netty", message);
        }

        Map<String, String> response = new java.util.HashMap<>();
        String selector;
        try {
            // never a stdin message, whatever the client's selector (StreamSelectors.NO_STDIN)
            selector = StreamSelectors.subscriptionSelector(si);
        } catch (IllegalArgumentException refused) {
            // a selector the client could shape would let it subscribe to other streams, or write reserved properties
            refuse(ctx, refused.getMessage());
            return;
        }
        String stdin = stdinRefusal(si);
        if (stdin != null) {
            refuse(ctx, stdin);
            return;
        }
        String roleRefusal = userStreamRefusal(si, principal);
        if (roleRefusal != null) {
            refuse(ctx, roleRefusal);
            return;
        }

        // Shard is derived from the stream ident -- the one key both the publishing endpoint and the
        // subscribing endpoint share -- so both land on the same shard-topic (global.event.<shard>).
        this.shard = plugin.getAgentService().getDataPlaneService().shardFor(si.getIdentId());
        response.putAll(activationReply(() -> createListener(ctx, si, selector)));
        String code = response.get("status_code");
        if (STATUS_ACTIVE.equals(code) || STATUS_SUBSCRIPTION_REFUSED.equals(code)) {
            // a refused subscription still leaves the stream usable for sending (the client may be a pure sender
            // of a stream bound to someone else); the client decides whether a refusal is an error for it
            this.streamInfo = si;
            this.active = true;
        }
        if (STATUS_SUBSCRIPTION_REFUSED.equals(code)) {
            logger.warn("DataPlaneWsHandler: the broker refused this client's subscription (" + selector + "): "
                    + response.get("status_desc") + " -- the stream stays open for sending");
        }
        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
    }

    /** Why {@code caller} may not use the dataplane of an agent in {@code agentTenant}, or null when it may. */
    static String tenantRefusal(Authenticator.Principal caller, String agentTenant) {
        if (caller == null) return "no authenticated caller";
        if (agentTenant == null || agentTenant.isEmpty()) return "this agent's tenant is not known yet";
        if (!agentTenant.equals(caller.tenant)) {
            return "the dataplane of tenant " + agentTenant + " is not open to " + caller;
        }
        return null;
    }

    /**
     * A dataplane listener that is also told (JMS ExceptionListener) when the controller cannot restore its subscription
     * after a reconnect because the broker refused it.
     */
    interface RefusalAwareListener extends MessageListener, jakarta.jms.ExceptionListener { }

    /** Close a client's stream because its subscription was refused: WebSocket close 1008 (policy violation) with the reason. */
    static void closeRefused(Channel ch, String reason) {
        String r = "Subscription refused: " + (reason == null ? "" : reason);
        byte[] b = r.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length > 123) r = new String(java.util.Arrays.copyOf(b, 120), java.nio.charset.StandardCharsets.UTF_8) + "...";
        ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.CloseWebSocketFrame(1008, r))
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    /** Activation reply: the listener is active. */
    public static final String STATUS_ACTIVE = "10";
    /**
     * Activation reply: the broker refused the subscription by policy (tenant isolation, or a stream bound to another
     * recipient with cresco_to). The connection stays open for sending; {@code status_desc} carries the broker's reason
     * and {@code subscription} is {@code refused}.
     */
    public static final String STATUS_SUBSCRIPTION_REFUSED = "40";

    /** The activation reply for one attempt to create the dataplane listener. */
    static Map<String, String> activationReply(java.util.concurrent.Callable<Boolean> createListener) {
        Map<String, String> r = new java.util.HashMap<>();
        try {
            if (Boolean.TRUE.equals(createListener.call())) {
                r.put("status_code", STATUS_ACTIVE);
                r.put("status_desc", "Listener Active");
            } else {
                r.put("status_code", "9");
                r.put("status_desc", "Could not activate listener");
            }
        } catch (SecurityException refused) {
            r.put("status_code", STATUS_SUBSCRIPTION_REFUSED);
            r.put("status_desc", "Subscription refused: " + refused.getMessage());
            r.put("subscription", "refused");
        } catch (Exception ex) {
            r.put("status_code", "90");
            r.put("status_desc", String.valueOf(ex.getMessage()));
        }
        return r;
    }

    private boolean createListener(ChannelHandlerContext ctx, StreamInfo si, String streamQuery) {
        try {
            final Channel ch = ctx.channel();
            MessageListener ml = new RefusalAwareListener() {
                @Override
                public void onException(jakarta.jms.JMSException refused) {
                    // the controller could not restore this subscription after a reconnect (the broker refused it):
                    // close the stream; the client reconnects and its activation reply says why
                    active = false;
                    closeRefused(ch, refused.getMessage());
                }

                @Override
                public void onMessage(Message msg) {
                    try {
                        // EGRESS: the JMS session is shared with every other dataplane listener on this
                        // agent, so this never blocks. Frames used to be dropped as soon as the channel
                        // passed its 2 MiB write high-water mark, i.e. eight 256 KiB frames: a burst that
                        // outran the consumer by a few milliseconds lost frames (2026-09-26, 2-node, 800 x
                        // 256 KiB: 4 of 70 runs lost 1-8 frames, mostly the first run after a cold start).
                        // Now they queue within a per-consumer byte budget (EgressBudget) and only an
                        // overloaded consumer loses frames, each one counted.
                        if (msg instanceof TextMessage) {
                            deliver(ch, new TextWebSocketFrame(Unpooled.copiedBuffer(((TextMessage) msg).getText(),
                                    java.nio.charset.StandardCharsets.UTF_8)));
                        } else if (msg instanceof jakarta.jms.MapMessage) {
                            // executor exit events and other map events: their entries as JSON (were dropped)
                            deliver(ch, new TextWebSocketFrame(Unpooled.copiedBuffer(
                                    DataPlaneFrames.mapJson((jakarta.jms.MapMessage) msg), java.nio.charset.StandardCharsets.UTF_8)));
                        } else if (DataPlaneFrames.isTransferEnd(msg)) {
                            // a streamfile terminal frame: its status and SHA-384, for the client to verify the transfer
                            deliver(ch, new TextWebSocketFrame(Unpooled.copiedBuffer(
                                    DataPlaneFrames.transferEndJson(msg), java.nio.charset.StandardCharsets.UTF_8)));
                        } else if (msg instanceof BytesMessage) {
                            String transferId = msg.getStringProperty("transfer_id");
                            long dataSize = ((BytesMessage) msg).getBodyLength();
                            byte[] bytes = new byte[(int) dataSize];
                            ((BytesMessage) msg).readBytes(bytes);
                            if (msg.getStringProperty("seq_num") != null) {
                                String seqNum = String.format("%1$" + 6 + "s", msg.getStringProperty("seq_num")).replace(' ', '0');
                                bytes = Bytes.concat(seqNum.getBytes(), bytes);
                            }
                            if (transferId != null) {
                                bytes = Bytes.concat(transferId.getBytes(), bytes);
                            }
                            deliver(ch, new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
                        }
                    } catch (Exception ex) {
                        logger.error("DataPlaneWsHandler listener.onMessage failed: " + ex.getMessage());
                    }
                }
            };

            this.listenerId = plugin.getAgentService().getDataPlaneService()
                    .addMessageListener(TopicType.GLOBAL, ml, streamQuery, shard);
            si.setListenerId(listenerId);
            return listenerId != null;
        } catch (SecurityException refused) {
            throw refused;                                   // the broker refused it: reported to the client as such
        } catch (Exception ex) {
            logger.error("DataPlaneWsHandler.createListener failed: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable() && droppedFrames.get() > 0) {
            logger.warn("DataPlaneWsHandler: WS consumer drained after dropping "
                    + droppedFrames.getAndSet(0) + " frames (wsapi.dataplane.egress.dropped total=" + EGRESS_DROPPED.get() + ")");
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ACTIVE_CONNECTIONS.updateAndGet(v -> v > 0 ? v - 1 : 0);
        if (listenerId != null) {
            try { plugin.getAgentService().getDataPlaneService().removeMessageListener(listenerId); } catch (Exception ignore) {}
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("DataPlaneWsHandler error: " + cause.getMessage());
        ctx.close();
    }
}
