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

    private final PluginBuilder plugin;
    private final CLogger logger;

    private volatile boolean active = false;
    private volatile String listenerId;
    private volatile StreamInfo streamInfo;
    private volatile int shard = 0;
    private Channel channel;

    public DataPlaneWsHandler(PluginBuilder plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(DataPlaneWsHandler.class.getName(), CLogger.Level.Info);
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
        try {
            if (createListener(ctx, si)) {
                this.streamInfo = si;
                this.active = true;
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

    private boolean createListener(ChannelHandlerContext ctx, StreamInfo si) {
        try {
            final Channel ch = ctx.channel();
            MessageListener ml = new MessageListener() {
                @Override
                public void onMessage(Message msg) {
                    try {
                        if (msg instanceof TextMessage) {
                            ch.writeAndFlush(new TextWebSocketFrame(((TextMessage) msg).getText()));
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
                            ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
                        }
                    } catch (Exception ex) {
                        logger.error("DataPlaneWsHandler listener.onMessage failed: " + ex.getMessage());
                    }
                }
            };

            String streamQuery = (si.getStream_query() != null)
                    ? si.getStream_query()
                    : si.getIdentKey() + "='" + si.getIdentId() + "'";
            this.listenerId = plugin.getAgentService().getDataPlaneService()
                    .addMessageListener(TopicType.GLOBAL, ml, streamQuery, shard);
            si.setListenerId(listenerId);
            return true;
        } catch (Exception ex) {
            logger.error("DataPlaneWsHandler.createListener failed: " + ex.getMessage());
            return false;
        }
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
