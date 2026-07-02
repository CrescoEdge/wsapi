package io.cresco.wsapi.netty;

import io.cresco.library.plugin.PluginBuilder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.CharsetUtil;

/**
 * First handler after HTTP aggregation. Authenticates the {@code cresco_service_key} header on the
 * upgrade request (matching the old servlet AuthFilter), then, for a known WebSocket path,
 * reconfigures the pipeline with the Netty WebSocket protocol handler + a whole-message frame
 * aggregator + the endpoint handler, and re-fires the request so the handshake proceeds.
 */
public class WsRouter extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final String DATAPLANE_PATH = "/api/dataplane";
    static final String APISOCKET_PATH = "/api/apisocket";
    static final String LOGSTREAMER_PATH = "/api/logstreamer";
    static final int MAX_WS_MESSAGE = 1 << 30; // 1GB, matches jakarta setMaxBinaryMessageBufferSize

    private final PluginBuilder plugin;
    private final int ioBufferBytes;

    public WsRouter(PluginBuilder plugin, int ioBufferBytes) {
        this.plugin = plugin;
        this.ioBufferBytes = ioBufferBytes;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        // --- auth: cresco_service_key header must match server config ---
        String provided = req.headers().get("cresco_service_key");
        String expected = plugin.getConfig().getStringParam("cresco_service_key");
        if (expected == null) { deny(ctx, "Missing server-side cresco_service_key configuration"); return; }
        if (provided == null) { deny(ctx, "Missing cresco_service_key request header"); return; }
        if (!expected.equals(provided)) { deny(ctx, "cresco_service_key mismatch"); return; }

        // --- route by path ---
        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = (q >= 0) ? uri.substring(0, q) : uri;

        io.netty.channel.ChannelHandler endpoint;
        switch (path) {
            case DATAPLANE_PATH:   endpoint = new DataPlaneWsHandler(plugin); break;
            case APISOCKET_PATH:   endpoint = new ApiSocketWsHandler(plugin); break;
            case LOGSTREAMER_PATH: endpoint = new LogStreamerWsHandler(plugin); break;
            default: notFound(ctx); return;
        }

        WebSocketServerProtocolConfig cfg = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(path)
                .maxFramePayloadLength(MAX_WS_MESSAGE)
                .allowExtensions(false)          // permessage-deflate stays OFF
                .build();

        ctx.pipeline().addLast(new WebSocketServerProtocolHandler(cfg));
        ctx.pipeline().addLast(new WebSocketFrameAggregator(MAX_WS_MESSAGE)); // whole-message delivery
        ctx.pipeline().addLast(endpoint);
        ctx.pipeline().remove(this); // router done; hand the upgrade to the protocol handler

        // re-fire the (retained) request so WebSocketServerProtocolHandler performs the handshake
        ctx.fireChannelRead(req.retain());
    }

    private void deny(ChannelHandlerContext ctx, String msg) {
        send(ctx, HttpResponseStatus.UNAUTHORIZED, msg);
    }

    private void notFound(ChannelHandlerContext ctx) {
        send(ctx, HttpResponseStatus.NOT_FOUND, "not found");
    }

    private void send(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        HttpUtil.setContentLength(resp, resp.content().readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
