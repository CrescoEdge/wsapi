package io.cresco.wsapi.netty;

import com.google.common.primitives.Bytes;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
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

    public DataPlaneWsHandler(PluginBuilder plugin) {
        this.plugin = plugin;
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
                    // data text -> broker
                    DATAPLANE_BYTES.addAndGet(message.length());
                    DATAPLANE_MESSAGES.incrementAndGet();
                    TextMessage tm = plugin.getAgentService().getDataPlaneService().createTextMessage();
                    tm.setText(message);
                    tm.setStringProperty(streamInfo.getIdentKey(), streamInfo.getIdentId());
                    tm.setStringProperty(streamInfo.getIoTypeKey(), streamInfo.getInputId());
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

    private void handshake(ChannelHandlerContext ctx, String message) {
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

        // Shard is derived from the stream ident -- the one key both the publishing endpoint and the
        // subscribing endpoint share -- so both land on the same shard-topic (global.event.<shard>).
        this.shard = plugin.getAgentService().getDataPlaneService().shardFor(si.getIdentId());

        Map<String, String> response = new java.util.HashMap<>();
        String selector;
        try {
            selector = StreamSelectors.selectorFor(si);
        } catch (IllegalArgumentException refused) {
            // a selector the client could shape would let it subscribe to other streams: refuse and hang up
            logger.warn("DataPlaneWsHandler: handshake refused: " + refused.getMessage());
            response.put("status_code", "9");
            response.put("status_desc", "refused: " + refused.getMessage());
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response))).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }
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
            MessageListener ml = new MessageListener() {
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
