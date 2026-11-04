package io.cresco.wsapi.netty;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.Authenticator;
import io.cresco.wsapi.auth.CredentialStore;
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
 * <p>Admission, in order: the path; the browser {@code Origin} (refused unless it is wsapi's own or allowlisted:
 * {@code wsapi_allowed_origins}); the cheap checks on the network thread ({@link Authenticator#authenticateFast}:
 * refusals, malformed headers, the service key, remembered passwords); then a password that needs PBKDF2 goes to the
 * {@link Gate}'s bounded pool, at most {@code wsapi_auth_pending_per_source} at a time per source, so one source cannot
 * fill the pool (503 for it alone when it tries); finally the caller's open connections
 * ({@code wsapi_max_connections_per_user}, {@code wsapi_max_connections_per_admin}). wsapi never sends a
 * {@code WWW-Authenticate} challenge: a browser must not offer cached credentials to it.
 */
public class WsRouter extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final String DATAPLANE_PATH = "/api/dataplane";
    static final String APISOCKET_PATH = "/api/apisocket";
    static final String LOGSTREAMER_PATH = "/api/logstreamer";
    /**
     * Default largest message per endpoint (bytes). The API socket carries RPCs, including plugin jar uploads (base64,
     * about 4/3 of the jar: a 26 MB controller.jar is about 35 MB); the log stream only short configuration lines; the
     * dataplane bulk frames (pycrescolib reads up to 64 MiB). A larger message closes the connection with WebSocket
     * status 1009 (message too big). Every endpoint used to accept 1 GiB, buffered whole per connection.
     */
    static final int DEFAULT_API_MAX_MESSAGE = 64 << 20;
    static final int DEFAULT_LOGSTREAMER_MAX_MESSAGE = 64 << 10;
    static final int DEFAULT_DATAPLANE_MAX_MESSAGE = 64 << 20;
    /** The largest cap a setting may ask for (the old fixed limit). */
    static final int MAX_WS_MESSAGE = 1 << 30;

    /** Authentication for every upgrade: the authenticator, its worker pool, admission limits and the refusal log. */
    public static final class Gate {
        final Authenticator authenticator;
        final ApiGateway gateway;
        private final ExecutorService pool;
        private final CLogger logger;
        private final Map<String, Long> lastRateLimitLog = new ConcurrentHashMap<>();
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> pendingBySource = new ConcurrentHashMap<>();
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> connectionsByCaller = new ConcurrentHashMap<>();
        private volatile int pendingPerSource = 2;
        private volatile int maxConnectionsUser = 64;
        private volatile int maxConnectionsAdmin = 256;
        private volatile java.util.Set<String> allowedOrigins = java.util.Set.of();

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

        /**
         * Admission limits: password checks pending per source, open connections per user and per admin, and the browser
         * Origins allowed besides wsapi's own (exact {@code scheme://host[:port]}, case-insensitive).
         */
        public Gate limits(int pendingPerSource, int maxConnectionsUser, int maxConnectionsAdmin, java.util.Collection<String> origins) {
            this.pendingPerSource = Math.max(1, pendingPerSource);
            this.maxConnectionsUser = Math.max(1, maxConnectionsUser);
            this.maxConnectionsAdmin = Math.max(1, maxConnectionsAdmin);
            java.util.Set<String> o = new java.util.HashSet<>();
            if (origins != null) for (String x : origins) if (x != null && !x.isBlank()) o.add(normalizeOrigin(x));
            this.allowedOrigins = java.util.Set.copyOf(o);
            return this;
        }

        /** Reserve a password-check slot for {@code source}; false when it already has its share pending. */
        boolean reservePending(String source) {
            java.util.concurrent.atomic.AtomicInteger n = pendingBySource.computeIfAbsent(source, k -> new java.util.concurrent.atomic.AtomicInteger());
            if (n.incrementAndGet() > pendingPerSource) {
                releasePending(source);
                return false;
            }
            return true;
        }

        void releasePending(String source) {
            pendingBySource.computeIfPresent(source, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
        }

        /** Count a connection of {@code p}; false (nothing counted) when it already has its maximum open. */
        boolean openConnection(Authenticator.Principal p) {
            int max = p.isAdmin() ? maxConnectionsAdmin : maxConnectionsUser;
            java.util.concurrent.atomic.AtomicInteger n = connectionsByCaller.computeIfAbsent(p.key(), k -> new java.util.concurrent.atomic.AtomicInteger());
            if (n.incrementAndGet() > max) {
                closeConnection(p);
                return false;
            }
            return true;
        }

        void closeConnection(Authenticator.Principal p) {
            connectionsByCaller.computeIfPresent(p.key(), (k, n) -> n.decrementAndGet() <= 0 ? null : n);
        }

        // ---- revocation: an open connection lives only while its credential would still be accepted ----

        /** One open, authenticated connection. */
        static final class Open {
            final Authenticator.Principal principal;
            final String path, source;

            Open(Authenticator.Principal principal, String path, String source) {
                this.principal = principal;
                this.path = path;
                this.source = source;
            }
        }

        /** Default and bounds of how often every open connection's credential is checked again (ms). */
        static final long DEFAULT_REVOCATION_CHECK_MS = 1000L, MIN_REVOCATION_CHECK_MS = 250L, MAX_REVOCATION_CHECK_MS = 5000L;
        /** The close reason a client sees (WebSocket 1008): never which part of a credential changed. */
        static final String REVOKED_REASON = "credentials revoked or changed: authenticate again";
        /** Connections closed because their credential was revoked or changed (since start). */
        public static final java.util.concurrent.atomic.AtomicLong REVOKED_CONNECTIONS = new java.util.concurrent.atomic.AtomicLong();

        private static final io.netty.util.AttributeKey<Boolean> REVOKED = io.netty.util.AttributeKey.valueOf("wsapi.revoked");
        private final Map<io.netty.channel.Channel, Open> open = new ConcurrentHashMap<>();
        private volatile long revocationCheckMs = DEFAULT_REVOCATION_CHECK_MS;
        private java.util.concurrent.ScheduledExecutorService revoker;      // started with the first connection
        private boolean stopped;

        /** How often every open connection's credential is checked again ({@code wsapi_revocation_check_ms}, 250-5000). */
        public Gate revocationCheckMs(long ms) {
            this.revocationCheckMs = Math.max(MIN_REVOCATION_CHECK_MS, Math.min(MAX_REVOCATION_CHECK_MS, ms));
            return this;
        }

        /** Track an authenticated connection until it closes, and check its credential again from now on. */
        void register(io.netty.channel.Channel ch, Authenticator.Principal p, String path, String source) {
            open.put(ch, new Open(p, path, source));
            ch.closeFuture().addListener(f -> open.remove(ch));
            startRevoker();
        }

        private synchronized void startRevoker() {
            if (revoker != null || stopped) return;
            revoker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wsapi-revocation");
                t.setDaemon(true);
                return t;
            });
            revoker.scheduleWithFixedDelay(this::sweep, revocationCheckMs, revocationCheckMs, TimeUnit.MILLISECONDS);
        }

        /**
         * Close every open connection whose credential was revoked or changed ({@link Authenticator#revocation}), with
         * WebSocket status 1008; one WARN line per caller and reason.
         */
        void sweep() {
            try {
                Map<String, int[]> closed = new java.util.LinkedHashMap<>();
                for (Map.Entry<io.netty.channel.Channel, Open> e : open.entrySet()) {
                    Open o = e.getValue();
                    String why;
                    try {
                        why = authenticator.revocation(o.principal);
                    } catch (RuntimeException failed) {
                        why = "its credential could not be checked again (" + failed.getClass().getSimpleName() + ")";
                    }
                    if (why == null) continue;
                    if (closeRevoked(e.getKey())) {
                        closed.computeIfAbsent(o.principal + " (" + o.principal.credentialName() + "): " + why, k -> new int[1])[0]++;
                    }
                }
                for (Map.Entry<String, int[]> c : closed.entrySet()) {
                    warn("wsapi closed " + c.getValue()[0] + " open connection(s) of " + c.getKey());
                }
            } catch (RuntimeException e) {
                warn("wsapi revocation check failed: " + e);
            }
        }

        /**
         * May {@code p} still be served on {@code ch}? Checked for every message a client sends, and before an RPC's reply is
         * written: false (and the connection is being closed) once its credential was revoked or changed.
         */
        boolean admit(io.netty.channel.Channel ch, Authenticator.Principal p) {
            String why;
            try {
                why = authenticator.revocation(p);
            } catch (RuntimeException failed) {
                why = "its credential could not be checked again (" + failed.getClass().getSimpleName() + ")";
            }
            if (why == null) return true;
            Open o = ch == null ? null : open.get(ch);
            if (ch != null && closeRevoked(ch)) {
                warn("wsapi closed " + (o == null ? "a connection" : o.path + " from " + o.source) + " of " + p + " ("
                        + p.credentialName() + "): " + why);
            }
            return false;
        }

        /** Close a connection whose credential was revoked: 1008 with {@link #REVOKED_REASON}. True when this call closed it. */
        private boolean closeRevoked(io.netty.channel.Channel ch) {
            open.remove(ch);
            if (ch.attr(REVOKED).setIfAbsent(Boolean.TRUE) != null) return false;      // already being closed
            REVOKED_CONNECTIONS.incrementAndGet();
            ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.CloseWebSocketFrame(
                    io.netty.handler.codec.http.websocketx.WebSocketCloseStatus.POLICY_VIOLATION, REVOKED_REASON))
                    .addListener(ChannelFutureListener.CLOSE);
            return true;
        }

        /** Open connections being checked (for tests and the health of the check). */
        int openConnections() { return open.size(); }

        int connections(Authenticator.Principal p) {
            java.util.concurrent.atomic.AtomicInteger n = connectionsByCaller.get(p.key());
            return n == null ? 0 : n.get();
        }

        /**
         * May a request with this {@code Origin} (null: not a browser) and {@code Host} upgrade? No Origin: yes. An Origin
         * naming wsapi itself (the Host the request was sent to): yes (it is not a web page's). An allowlisted Origin: yes.
         * Any other browser Origin: no, so a web page cannot open wsapi with a browser's cached credentials.
         */
        boolean originAllowed(String origin, String host) {
            if (origin == null) return true;
            String o = normalizeOrigin(origin);
            if (allowedOrigins.contains(o)) return true;
            if (host == null || host.isBlank()) return false;
            try {
                java.net.URI u = java.net.URI.create(o);
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
                if (!scheme.equals("https") && !scheme.equals("wss")) return false;
                int port = u.getPort() < 0 ? 443 : u.getPort();
                String h = host.trim().toLowerCase(java.util.Locale.ROOT);
                String hostName;
                int hostPort = 443;
                if (h.startsWith("[")) {
                    int end = h.indexOf(']');
                    hostName = h.substring(0, end + 1);
                    if (h.length() > end + 2 && h.charAt(end + 1) == ':') hostPort = Integer.parseInt(h.substring(end + 2));
                } else {
                    int c = h.lastIndexOf(':');
                    hostName = c > 0 ? h.substring(0, c) : h;
                    if (c > 0) hostPort = Integer.parseInt(h.substring(c + 1));
                }
                String originHost = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
                return originHost.equals(hostName) && port == hostPort;
            } catch (RuntimeException bad) {
                return false;
            }
        }

        static String normalizeOrigin(String o) {
            String t = o.trim().toLowerCase(java.util.Locale.ROOT);
            while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            return t;
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
            if (CredentialStore.SERVICE_KEY_USER.equals(r.attemptedUser) && authenticator != null) {
                String alarm = authenticator.serviceKeyAlarm();
                if (alarm != null) logger.error("wsapi ALARM: " + alarm);
            }
        }

        void accepted(String source, String path, Authenticator.Principal p) {
            if (logger != null) logger.info("wsapi " + path + " from " + source + " authenticated as " + p + (p.serviceKey ? " (service key)" : ""));
        }

        void warn(String msg) {
            if (logger != null) logger.warn(msg);
        }

        public void shutdown() {
            pool.shutdownNow();
            synchronized (this) {
                stopped = true;
                if (revoker != null) revoker.shutdownNow();
                revoker = null;
            }
        }
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
        String source = sourceOf(ctx.channel().remoteAddress());
        String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        if (!gate.originAllowed(origin, req.headers().get(HttpHeaderNames.HOST))) {
            gate.warn("wsapi refused " + path + " from " + source + ": browser Origin " + sanitize(origin)
                    + " is not allowed (wsapi_allowed_origins)");
            send(ctx, HttpResponseStatus.FORBIDDEN, "forbidden: Origin not allowed");
            return;
        }
        String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        String serviceKey = req.headers().get(Authenticator.SERVICE_KEY_HEADER);

        // everything that needs no PBKDF2 is decided here, on the network thread
        Authenticator.Result fast;
        try {
            fast = gate.authenticator.authenticateFast(authorization, serviceKey, source);
        } catch (RuntimeException e) {
            send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "authentication error");
            return;
        }
        if (fast != null) {
            req.retain();
            finish(ctx, req, path, source, fast);
            return;
        }
        // a password check: bounded per source, then the bounded pool
        if (!gate.reservePending(io.cresco.wsapi.auth.Sources.bucket(source))) {
            gate.warn("wsapi refused " + path + " from " + source + ": too many password checks pending from this source");
            send(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, "too many logins in progress from this address; retry shortly");
            return;
        }
        req.retain();
        ctx.channel().config().setAutoRead(false);
        try {
            gate.submit(() -> {
                Authenticator.Result r;
                try {
                    r = gate.authenticator.authenticate(authorization, serviceKey, source);
                } catch (RuntimeException e) {
                    r = null;
                } finally {
                    gate.releasePending(io.cresco.wsapi.auth.Sources.bucket(source));
                }
                final Authenticator.Result result = r;
                try {
                    ctx.executor().execute(() -> {
                        ctx.channel().config().setAutoRead(true);
                        finish(ctx, req, path, source, result);
                    });
                } catch (RejectedExecutionException closing) {
                    req.release();
                }
            });
        } catch (RejectedExecutionException busy) {
            gate.releasePending(io.cresco.wsapi.auth.Sources.bucket(source));
            req.release();
            ctx.channel().config().setAutoRead(true);
            send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "wsapi is busy authenticating; retry shortly");
        }
    }

    private void finish(ChannelHandlerContext ctx, FullHttpRequest req, String path, String source, Authenticator.Result r) {
        try {
            if (!ctx.channel().isActive()) return;
            if (r == null) {
                send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "authentication error");
                return;
            }
            if (!r.ok()) {
                gate.refused(source, path, r);
                deny(ctx, r);
                return;
            }
            Authenticator.Principal p = r.principal;
            if (!gate.openConnection(p)) {
                gate.warn("wsapi refused " + path + " from " + source + " for " + p + ": it has its maximum of open connections ("
                        + (p.isAdmin() ? "wsapi_max_connections_per_admin" : "wsapi_max_connections_per_user") + ")");
                send(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, "too many open connections for this user; close some first");
                return;
            }
            ctx.channel().closeFuture().addListener(f -> gate.closeConnection(p));
            gate.register(ctx.channel(), p, path, source);     // checked again until it closes: revocation reaches it
            gate.accepted(source, path, p);

            io.netty.channel.ChannelHandler endpoint;
            switch (path) {
                case DATAPLANE_PATH:   endpoint = new DataPlaneWsHandler(plugin, p, gate); break;
                case APISOCKET_PATH:   endpoint = new ApiSocketWsHandler(plugin, p, gate); break;
                default:               endpoint = new LogStreamerWsHandler(plugin, p, gate); break;
            }

            String capKey = capKey(path);
            int cap = maxMessageBytes(plugin, path);
            WebSocketServerProtocolConfig cfg = WebSocketServerProtocolConfig.newBuilder()
                    .websocketPath(path)
                    .maxFramePayloadLength(cap)      // one frame over the cap: the decoder closes with 1009
                    .allowExtensions(false)          // permessage-deflate stays OFF
                    .build();

            ctx.pipeline().addLast(new WebSocketServerProtocolHandler(cfg));
            ctx.pipeline().addLast(new CappedFrameAggregator(cap, capKey)); // whole-message delivery, the same cap
            ctx.pipeline().addLast(endpoint);
            ctx.pipeline().remove(this); // router done; hand the upgrade to the protocol handler

            // re-fire the (retained) request so WebSocketServerProtocolHandler performs the handshake
            ctx.fireChannelRead(req.retain());
        } finally {
            req.release();
        }
    }

    /** The config key of an endpoint's message cap. */
    static String capKey(String path) {
        switch (path) {
            case DATAPLANE_PATH: return "wsapi_dataplane_max_message_bytes";
            case LOGSTREAMER_PATH: return "wsapi_logstreamer_max_message_bytes";
            default: return "wsapi_api_max_message_bytes";
        }
    }

    /** An endpoint's message cap: its config key, else the default; within [1 KiB, 1 GiB]. */
    static int maxMessageBytes(PluginBuilder plugin, String path) {
        int dflt = DATAPLANE_PATH.equals(path) ? DEFAULT_DATAPLANE_MAX_MESSAGE
                : LOGSTREAMER_PATH.equals(path) ? DEFAULT_LOGSTREAMER_MAX_MESSAGE : DEFAULT_API_MAX_MESSAGE;
        Integer v = null;
        try {
            v = plugin == null ? null : plugin.getConfig().getIntegerParam(capKey(path));
        } catch (RuntimeException ignore) {
            // not a number: the default
        }
        int cap = v == null ? dflt : v;
        return Math.max(1024, Math.min(MAX_WS_MESSAGE, cap));
    }

    /**
     * Aggregates fragmented messages up to the cap. A message whose fragments add up to more closes the connection
     * with WebSocket status 1009 and a reason naming the cap, instead of buffering it (the stock aggregator only raised
     * an exception and dropped the connection without a status).
     */
    static final class CappedFrameAggregator extends WebSocketFrameAggregator {
        private final int cap;
        private final String key;

        CappedFrameAggregator(int cap, String key) {
            super(cap);
            this.cap = cap;
            this.key = key;
        }

        @Override
        protected void handleOversizedMessage(ChannelHandlerContext ctx, io.netty.handler.codec.http.websocketx.WebSocketFrame oversized) {
            // the aggregator releases the oversized frame (and what it had gathered) itself
            ctx.writeAndFlush(new io.netty.handler.codec.http.websocketx.CloseWebSocketFrame(
                            io.netty.handler.codec.http.websocketx.WebSocketCloseStatus.MESSAGE_TOO_BIG,
                            "message larger than " + cap + " bytes (" + key + ")"))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** The source an upgrade is counted against: {@code "<bucket> <address>"} ({@link io.cresco.wsapi.auth.Sources}). */
    static String sourceOf(SocketAddress a) {
        if (a instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) a;
            java.net.InetAddress addr = i.getAddress();
            if (addr == null) return i.getHostString();
            return io.cresco.wsapi.auth.Sources.of(addr);
        }
        return String.valueOf(a);
    }

    private static String sanitize(String s) {
        if (s == null) return "-";
        String t = s.length() > 100 ? s.substring(0, 100) + "..." : s;
        return t.replaceAll("[^\\x20-\\x7e]", "?");
    }

    /** Constant-time comparison: String.equals returns at the first differing character, which leaks how much of a guess was right. */
    static boolean keyMatches(String expected, String provided) {
        return Authenticator.keyMatches(expected, provided);
    }

    private void deny(ChannelHandlerContext ctx, Authenticator.Result r) {
        HttpResponseStatus st = HttpResponseStatus.valueOf(r.status);
        FullHttpResponse resp = response(st, r.clientMessage);
        // never a WWW-Authenticate challenge: a browser would offer cached credentials to wsapi on a web page's behalf
        if (r.status == 429) resp.headers().set(HttpHeaderNames.RETRY_AFTER, String.valueOf(r.retryAfterSeconds));
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    private void notFound(ChannelHandlerContext ctx) {
        send(ctx, HttpResponseStatus.NOT_FOUND, "not found");
    }

    private void send(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        ctx.writeAndFlush(response(status, body)).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * An error answer to an upgrade: the reason is the body and, when it is short printable ASCII, also the status line's
     * reason phrase ("HTTP/1.1 429 too many open connections for this user; ..."), since some WebSocket clients (Netty's)
     * report a refused upgrade's status line and headers but not its body.
     */
    static FullHttpResponse response(HttpResponseStatus status, String body) {
        HttpResponseStatus line = status;
        if (body != null && !body.isEmpty() && body.length() <= 200 && body.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            line = new HttpResponseStatus(status.code(), body);
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, line,
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
