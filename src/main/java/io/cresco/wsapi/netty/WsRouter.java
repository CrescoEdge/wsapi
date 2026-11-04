package io.cresco.wsapi.netty;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.Authenticator;
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * First handler after HTTP aggregation. Authenticates the upgrade request ({@link Authenticator}: per-user
 * {@code Authorization: Basic} credentials or the legacy {@code cresco_service_key}), then, for a known WebSocket path,
 * reconfigures the pipeline with the Netty WebSocket protocol handler + a whole-message frame aggregator + the endpoint
 * handler (which knows the authenticated caller), and re-fires the request so the handshake proceeds.
 *
 * <p>Authentication runs on the {@link Gate}'s bounded pool, never on the event loop: a PBKDF2 check takes a few
 * hundred milliseconds. The channel stops reading while it runs. A full pool answers 503.
 */
public class WsRouter extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final String DATAPLANE_PATH = "/api/dataplane";
    static final String APISOCKET_PATH = "/api/apisocket";
    static final String LOGSTREAMER_PATH = "/api/logstreamer";
    static final int MAX_WS_MESSAGE = 1 << 30; // 1GB, matches jakarta setMaxBinaryMessageBufferSize

    /** Authentication for every upgrade: the authenticator, its worker pool and the refusal log. */
    public static final class Gate {
        final Authenticator authenticator;
        final ApiGateway gateway;
        private final ExecutorService pool;
        private final CLogger logger;
        private final Map<String, Long> lastRateLimitLog = new ConcurrentHashMap<>();

        public Gate(Authenticator authenticator, ApiGateway gateway, int threads, int queue, CLogger logger) {
            this.authenticator = authenticator;
            this.gateway = gateway;
            this.logger = logger;
            this.pool = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(Math.max(1, queue)),
                    r -> {
                        Thread t = new Thread(r, "wsapi-auth");
                        t.setDaemon(true);
                        return t;
                    }, new ThreadPoolExecutor.AbortPolicy());
            ((ThreadPoolExecutor) pool).allowCoreThreadTimeOut(true);
        }

        void submit(Runnable r) throws RejectedExecutionException { pool.execute(r); }

        /** Logs a refusal (never a secret). A rate-limited source is logged at most once a minute. */
        void refused(String source, String path, Authenticator.Result r) {
            if (logger == null) return;
            if (r.status == 429) {
                long now = System.currentTimeMillis();
                Long last = lastRateLimitLog.get(source);
                if (last != null && now - last < 60_000L) return;
                if (lastRateLimitLog.size() > 10_000) lastRateLimitLog.clear();
                lastRateLimitLog.put(source, now);
            }
            logger.warn("wsapi refused " + path + " from " + source + " as " + (r.attemptedUser == null ? "-" : r.attemptedUser)
                    + ": " + r.logReason + " (HTTP " + r.status + ")");
        }

        void accepted(String source, String path, Authenticator.Principal p) {
            if (logger != null) logger.info("wsapi " + path + " from " + source + " authenticated as " + p + (p.serviceKey ? " (service key)" : ""));
        }

        public void shutdown() { pool.shutdownNow(); }
    }

    private final PluginBuilder plugin;
    private final int ioBufferBytes;
    private final Gate gate;

    public WsRouter(PluginBuilder plugin, int ioBufferBytes, Gate gate) {
        this.plugin = plugin;
        this.ioBufferBytes = ioBufferBytes;
        this.gate = gate;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = (q >= 0) ? uri.substring(0, q) : uri;
        if (!DATAPLANE_PATH.equals(path) && !APISOCKET_PATH.equals(path) && !LOGSTREAMER_PATH.equals(path)) {
            notFound(ctx);
            return;
        }
        if (gate == null) {                       // never serve without an authenticator (fail closed)
            send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "wsapi authentication is not configured");
            return;
        }
        String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        String serviceKey = req.headers().get(Authenticator.SERVICE_KEY_HEADER);
        String source = sourceOf(ctx.channel().remoteAddress());

        req.retain();
        ctx.channel().config().setAutoRead(false);
        try {
            gate.submit(() -> {
                Authenticator.Result r;
                try {
                    r = gate.authenticator.authenticate(authorization, serviceKey, source);
                } catch (RuntimeException e) {
                    r = null;
                }
                final Authenticator.Result result = r;
                try {
                    ctx.executor().execute(() -> finish(ctx, req, path, source, result));
                } catch (RejectedExecutionException closing) {
                    req.release();
                }
            });
        } catch (RejectedExecutionException busy) {
            req.release();
            ctx.channel().config().setAutoRead(true);
            send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "wsapi is busy authenticating; retry shortly");
        }
    }

    private void finish(ChannelHandlerContext ctx, FullHttpRequest req, String path, String source, Authenticator.Result r) {
        try {
            if (!ctx.channel().isActive()) return;
            ctx.channel().config().setAutoRead(true);
            if (r == null) {
                send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "authentication error");
                return;
            }
            if (!r.ok()) {
                gate.refused(source, path, r);
                deny(ctx, r);
                return;
            }
            gate.accepted(source, path, r.principal);

            io.netty.channel.ChannelHandler endpoint;
            switch (path) {
                case DATAPLANE_PATH:   endpoint = new DataPlaneWsHandler(plugin, r.principal); break;
                case APISOCKET_PATH:   endpoint = new ApiSocketWsHandler(plugin, r.principal, gate); break;
                default:               endpoint = new LogStreamerWsHandler(plugin, r.principal, gate); break;
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
        } finally {
            req.release();
        }
    }

    static String sourceOf(SocketAddress a) {
        if (a instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) a;
            return i.getAddress() != null ? i.getAddress().getHostAddress() : i.getHostString();
        }
        return String.valueOf(a);
    }

    /** Constant-time comparison: String.equals returns at the first differing character, which leaks how much of a guess was right. */
    static boolean keyMatches(String expected, String provided) {
        return Authenticator.keyMatches(expected, provided);
    }

    private void deny(ChannelHandlerContext ctx, Authenticator.Result r) {
        HttpResponseStatus st = HttpResponseStatus.valueOf(r.status);
        FullHttpResponse resp = response(st, r.clientMessage);
        if (r.status == 401) resp.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Basic realm=\"cresco-wsapi\", charset=\"UTF-8\"");
        if (r.status == 429) resp.headers().set(HttpHeaderNames.RETRY_AFTER, String.valueOf(r.retryAfterSeconds));
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    private void notFound(ChannelHandlerContext ctx) {
        send(ctx, HttpResponseStatus.NOT_FOUND, "not found");
    }

    private void send(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        ctx.writeAndFlush(response(status, body)).addListener(ChannelFutureListener.CLOSE);
    }

    private static FullHttpResponse response(HttpResponseStatus status, String body) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body == null ? "" : body, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        HttpUtil.setContentLength(resp, resp.content().readableBytes());
        return resp;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
