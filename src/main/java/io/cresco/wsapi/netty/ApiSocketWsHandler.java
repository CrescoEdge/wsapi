package io.cresco.wsapi.netty;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.security.Caller;
import io.cresco.library.security.TenantNamespace;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.auth.Authenticator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Netty port of {@code APISocket} — MsgEvent RPC/emit bridge. Text frame carries
 * {@code {"message_info":{...},"message_payload":{...}}}; builds the MsgEvent for the requested
 * type, then sends it for the authenticated client ({@link ApiGateway}): an RPC (reply with the response params as
 * JSON) or fire-and-forget. Neither is sent on the event loop, and each counts against a budget of its caller: an RPC
 * against its requests in flight, a send without a reply against its sends a second ({@link Sends}).
 *
 * <p>Before anything is sent: the reserved caller parameters ({@code cresco_caller}, {@code cresco_caller_tenant},
 * {@code cresco_caller_roles}) and the other parameters only infrastructure writes ({@link InternalParams}) are removed
 * from the client's payload, and a message that names another tenant ({@code cresco_tenant}) is refused unless the client
 * is an admin of a superuser tenant. A reply reaches the client without the fabric's own stamps ({@link InternalParams}).
 */
public class ApiSocketWsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Gson gson = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, String>>>() {}.getType();

    // sendRPC blocks up to the RPC timeout; RPCs are answered from this bounded pool, never on a Netty event loop (the
    // old CallerRunsPolicy ran an overflowing RPC on the event loop, so one caller's hung RPCs stopped every client's
    // traffic). A full pool, or a caller at its in-flight limit, gets an immediate "busy" (429) reply instead.
    // Responses may complete out of order: clients correlate replies (pycrescolib stamps client_rpc_id, echoed back)
    // or issue one request at a time.
    static final int RPC_THREADS = 128;
    static final int RPC_QUEUE = 512;
    // One thread per RPC in flight, at most RPC_THREADS + RPC_QUEUE of them (an RPC mostly waits for its reply, so a thread
    // per RPC is the same bound as 128 running and 512 queued), handed over through a SynchronousQueue: an idle thread
    // takes the next RPC at once, the most recently idle first. The fixed 128-thread pool with a FIFO queue made a new
    // thread for each of the first 128 RPCs and woke a different, cold, thread for every later one (about 0.1 ms of every
    // serial call). Full: refused at once ("busy"), never run on the event loop.
    private static final ThreadPoolExecutor RPC_POOL = new ThreadPoolExecutor(
            0, RPC_THREADS + RPC_QUEUE, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "wsapi-apisocket-rpc");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** RPCs each caller has in flight (all of the caller's connections together). */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Default in-flight RPC limits and timeout caps, by role. */
    static final int DEFAULT_MAX_INFLIGHT_USER = 8;
    static final int DEFAULT_MAX_INFLIGHT_ADMIN = 32;
    static final long DEFAULT_MAX_TIMEOUT_USER_MS = 120_000L;

    // Sends without a reply (message_info is_rpc false). An RPC counts against its caller's requests in flight; a send has
    // no reply to wait for, so it counts against a rate: at most wsapi_send_max_per_second_user / _admin a second for each
    // caller (all its connections together; one second's worth at once). They are handed to the controller off the event
    // loop (its send can wait: a control plane that is reconnecting holds a sender for seconds), one at a time for each
    // caller, in the order they arrived. What arrives faster waits, never dropped: a connection with MAX_SENDS_WAITING
    // sends (or MAX_SEND_BYTES_WAITING bytes of them) waiting is not read from until half are sent, so TCP holds the
    // client; and what waits stays counted as the caller's inbound bytes in flight (InboundBudget). Sent on the event
    // loop and uncounted, the actions an RPC is limited for went out at wire rate.
    static final int DEFAULT_MAX_SENDS_PER_SECOND_USER = 50;
    static final int DEFAULT_MAX_SENDS_PER_SECOND_ADMIN = 500;
    static final int MAX_SENDS_WAITING = 64;
    static final long MAX_SEND_BYTES_WAITING = 1L << 20;
    /** Each caller's sends without a reply (all of the caller's connections together). */
    static final java.util.concurrent.ConcurrentHashMap<String, Sends> SENDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A send waiting for its caller's turn: the connection it came from, the message, the bytes of its frame. */
    static final class Send {
        final ApiSocketWsHandler from;
        final ChannelHandlerContext ctx;
        final MsgEvent msg;
        final long bytes;

        Send(ApiSocketWsHandler from, ChannelHandlerContext ctx, MsgEvent msg, long bytes) {
            this.from = from;
            this.ctx = ctx;
            this.msg = msg;
            this.bytes = bytes;
        }
    }

    /** One caller's sends without a reply: in the order they arrived, one at a time, at most its rate. */
    static final class Sends {
        private final java.util.concurrent.ConcurrentLinkedQueue<Send> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
        // the rate: sends the caller may make now (at most one second's worth), refilled as time passes
        private double budget;
        private long budgetAtNanos;
        private boolean started;

        /** Take one send from the rate; 0 when taken, else the nanoseconds until one is due. */
        synchronized long take(int perSecond, long nowNanos) {
            int rate = Math.max(1, perSecond);
            budget = started ? Math.min(rate, budget + (nowNanos - budgetAtNanos) * (rate / 1e9)) : rate;
            started = true;
            budgetAtNanos = nowNanos;
            if (budget >= 1.0) {
                budget -= 1.0;
                return 0L;
            }
            return Math.max(1L, (long) Math.ceil((1.0 - budget) * 1e9 / rate));
        }

        void add(Send s) {
            queue.add(s);
            drain();
        }

        /** Hand what waits to a pool thread, unless one is at it. */
        private void drain() {
            if (queue.isEmpty() || !running.compareAndSet(false, true)) return;
            try {
                RPC_POOL.execute(this::run);
            } catch (java.util.concurrent.RejectedExecutionException full) {
                running.set(false);
                later(50_000_000L);                              // every pool thread is taken: again in 50 ms
            }
        }

        private void later(long nanos) {
            io.netty.util.concurrent.GlobalEventExecutor.INSTANCE.schedule(this::drain, nanos, TimeUnit.NANOSECONDS);
        }

        private void run() {
            long wait = 0L;
            try {
                Send s;
                while ((s = queue.peek()) != null) {
                    wait = take(s.from.maxSendsPerSecond(), System.nanoTime());
                    if (wait > 0L) break;                        // the caller's rate is spent: the rest waits
                    queue.poll();
                    s.from.send(s);
                }
            } finally {
                running.set(false);
            }
            if (wait > 0L) later(wait);
            else drain();                                        // one that arrived as this thread was leaving
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger sendsWaiting = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong sendBytesWaiting = new java.util.concurrent.atomic.AtomicLong();
    private long lastHeldLogMs;                                  // event loop only

    /** Parameters only the controller may set (the verified caller); a client's values are dropped. */
    static final Set<String> RESERVED_PARAMS = Caller.RESERVED_PARAMS;

    private final PluginBuilder plugin;
    private final CLogger logger;
    private final Authenticator.Principal principal;
    private final WsRouter.Gate gate;

    public ApiSocketWsHandler(PluginBuilder plugin, Authenticator.Principal principal, WsRouter.Gate gate) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(ApiSocketWsHandler.class.getName(), CLogger.Level.Info);
        this.principal = principal;
        this.gate = gate;
    }

    /**
     * Vet a client's payload before it becomes a message: drop the reserved caller parameters (in place) and refuse a
     * message aimed at another tenant unless the caller may target it.
     *
     * @return null when the message may be sent, else why not
     */
    static String vet(Map<String, String> payload, Authenticator.Principal caller, Authenticator auth) {
        if (caller == null || auth == null) return "no authenticated caller";
        if (payload == null) return null;
        payload.keySet().removeIf(RESERVED_PARAMS::contains);
        InternalParams.stripRequest(payload);              // signatures, routing annotations, RPC plumbing: infrastructure's
        String target = payload.get(TenantNamespace.TENANT_PARAM);
        if (target != null && !auth.mayTarget(caller, target)) {
            return caller + " may not address tenant " + target;
        }
        return null;
    }

    /**
     * For a {@code user}, the region a message goes to must be this agent's own: an agent joins only a region
     * controller of its own tenant, so this agent's region is the one region known to be the user's tenant (the
     * controller does not tell wsapi the tenant of other regions). Admins are not limited here; the controller authorizes
     * every message again.
     *
     * <p>Decided from the message as it was built, which is what the routers act on, not from the client's
     * message_info: its destination must be this region, and a message flagged global is handled by the global
     * controller whatever destination it names, so it is allowed only while the global controller is in this region.
     * (Read from message_info, a global_controller_msgevent with this region as its region_id and no agent_id passed as
     * a message to this region, and was built without them: for the global controller, wherever that is.)
     *
     * @return null when the destination is allowed, else why not
     */
    static String destinationRefusal(MsgEvent built, Authenticator.Principal caller, String localRegion, String globalRegion) {
        if (caller == null || caller.isAdmin()) return null;
        if (built == null) return "no message";
        String region = built.getDstRegion();
        if (region == null || localRegion == null || !region.equals(localRegion)) {
            return caller + " may only address its own tenant's region " + localRegion + ", not " + region
                    + " (no other region is known to be in tenant " + caller.tenant + ")";
        }
        if (built.isGlobal() && !localRegion.equals(globalRegion)) {
            return caller + " may only address its own tenant's region " + localRegion + ", not the global controller in "
                    + globalRegion + " (no other region is known to be in tenant " + caller.tenant + ")";
        }
        return null;
    }

    private int maxInFlight() {
        try {
            return principal.isAdmin()
                    ? plugin.getConfig().getIntegerParam("wsapi_rpc_max_inflight_admin", DEFAULT_MAX_INFLIGHT_ADMIN)
                    : plugin.getConfig().getIntegerParam("wsapi_rpc_max_inflight_user", DEFAULT_MAX_INFLIGHT_USER);
        } catch (RuntimeException e) {
            return principal.isAdmin() ? DEFAULT_MAX_INFLIGHT_ADMIN : DEFAULT_MAX_INFLIGHT_USER;
        }
    }

    private int maxSendsPerSecond() {
        try {
            return principal.isAdmin()
                    ? plugin.getConfig().getIntegerParam("wsapi_send_max_per_second_admin", DEFAULT_MAX_SENDS_PER_SECOND_ADMIN)
                    : plugin.getConfig().getIntegerParam("wsapi_send_max_per_second_user", DEFAULT_MAX_SENDS_PER_SECOND_USER);
        } catch (RuntimeException e) {
            return principal.isAdmin() ? DEFAULT_MAX_SENDS_PER_SECOND_ADMIN : DEFAULT_MAX_SENDS_PER_SECOND_USER;
        }
    }

    /**
     * Queue a send without a reply for its caller's turn (event loop). Reading from this connection stops while too
     * many of its sends wait, and goes on when half are sent.
     */
    private void sendLater(ChannelHandlerContext ctx, MsgEvent request, long bytes) {
        gate.inbound.kept(principal.key(), bytes);
        int n = sendsWaiting.incrementAndGet();
        long b = sendBytesWaiting.addAndGet(bytes);
        if ((n >= MAX_SENDS_WAITING || b >= MAX_SEND_BYTES_WAITING) && ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(false);
            long now = System.currentTimeMillis();
            if (now - lastHeldLogMs >= 5000) {
                lastHeldLogMs = now;
                logger.warn("wsapi is not reading from a connection of " + principal + " while " + n + " of its sends (" + b
                        + " B) wait: a caller's sends without a reply are limited to " + maxSendsPerSecond()
                        + " a second (wsapi_send_max_per_second_" + (principal.isAdmin() ? "admin" : "user") + ")");
            }
        }
        SENDS.computeIfAbsent(principal.key(), k -> new Sends()).add(new Send(this, ctx, request, bytes));
    }

    /** Hand one waiting send to the controller (a pool thread), then read from its connection again if that was stopped. */
    private void send(Send s) {
        io.netty.channel.Channel ch = s.ctx.channel();
        try {
            // revoked while it waited: nothing more is sent for the revoked credential
            if (gate.admit(ch, principal)) gate.gateway.send(s.msg, principal);
        } catch (SecurityException refused) {
            // the controller would not send it for this caller (on_behalf_bundles)
            logger.error("ApiSocketWsHandler send refused for " + principal + ": " + refused.getMessage());
        } catch (Exception ex) {
            logger.error("ApiSocketWsHandler send: " + ex.getMessage());
        } finally {
            gate.inbound.released(principal.key(), s.bytes);
            int n = sendsWaiting.decrementAndGet();
            long b = sendBytesWaiting.addAndGet(-s.bytes);
            if (n <= MAX_SENDS_WAITING / 2 && b <= MAX_SEND_BYTES_WAITING / 2 && !ch.config().isAutoRead()) {
                try {
                    ch.eventLoop().execute(() -> {
                        if (sendsWaiting.get() <= MAX_SENDS_WAITING / 2 && sendBytesWaiting.get() <= MAX_SEND_BYTES_WAITING / 2
                                && ch.isActive()) {
                            ch.config().setAutoRead(true);
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException stopping) {
                    // the server is stopping: nothing more to read
                }
            }
        }
    }

    private long maxTimeoutMs() {
        if (principal.isAdmin()) return MAX_RPC_TIMEOUT_MS;
        try {
            long v = plugin.getConfig().getLongParam("wsapi_rpc_max_timeout_user_ms", DEFAULT_MAX_TIMEOUT_USER_MS);
            return Math.max(1_000L, Math.min(MAX_RPC_TIMEOUT_MS, v));
        } catch (RuntimeException e) {
            return DEFAULT_MAX_TIMEOUT_USER_MS;
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame)) return;
        // a credential revoked or changed since the upgrade: nothing more is served, the connection is being closed
        if (gate != null && !gate.admit(ctx.channel(), principal)) return;
        try {
            String message = ((TextWebSocketFrame) frame).text();
            Map<String, Map<String, String>> incoming = gson.fromJson(message, TYPE);
            Map<String, String> mi = incoming == null ? null : incoming.get("message_info");
            Map<String, String> payload = incoming == null ? null : incoming.get("message_payload");
            boolean isRPC = mi != null && Boolean.parseBoolean(mi.get("is_rpc"));
            String refusal = vet(payload, principal, gate == null ? null : gate.authenticator);
            // built before its destination is judged: the message itself says where it goes
            MsgEvent request = refusal == null ? buildMsgEvent(incoming) : null;
            if (refusal == null && request != null) {
                refusal = destinationRefusal(request, principal, plugin.getRegion(), globalRegion());
            }
            if (refusal != null) {
                logger.warn("wsapi refused a message from " + principal + ": " + refusal);
                if (isRPC) ctx.writeAndFlush(new TextWebSocketFrame(failure(payload, "403", "unauthorized: " + refusal)));
                return;
            }
            if (request == null) {
                if (isRPC) ctx.writeAndFlush(new TextWebSocketFrame(failure(payload, "400", "unknown or malformed message_info")));
                return;
            }
            for (String r : RESERVED_PARAMS) request.removeParam(r);
            if (isRPC) {
                long cap = maxTimeoutMs();
                long asked = rpcTimeoutMs(mi);
                long timeoutMs = asked > 0 ? Math.min(asked, cap) : (DEFAULT_RPC_TIMEOUT_MS <= cap ? 0 : cap);
                java.util.concurrent.atomic.AtomicInteger mine = IN_FLIGHT.computeIfAbsent(principal.key(), k -> new java.util.concurrent.atomic.AtomicInteger());
                int limit = maxInFlight();
                if (mine.incrementAndGet() > limit) {
                    mine.decrementAndGet();
                    ctx.writeAndFlush(new TextWebSocketFrame(failure(payload, "429", "busy: " + principal + " has " + limit
                            + " requests in flight (wsapi_rpc_max_inflight_" + (principal.isAdmin() ? "admin" : "user") + "); retry when one finishes")));
                    return;
                }
                Runnable task = () -> {
                    String r;
                    try {
                        MsgEvent response = gate.gateway.rpc(request, timeoutMs, principal);
                        r = (response == null) ? failure(payload, "504", "no reply within "
                                + (timeoutMs > 0 ? timeoutMs : DEFAULT_RPC_TIMEOUT_MS) + " ms; the operation may still complete")
                                : success(response);
                    } catch (SecurityException refused) {
                        // the controller would not send it for this caller (on_behalf_bundles), so nothing was sent
                        logger.error("ApiSocketWsHandler rpc refused for " + principal + ": " + refused.getMessage());
                        r = failure(payload, "403", "unauthorized: " + refused.getMessage());
                    } catch (Exception ex) {
                        logger.error("ApiSocketWsHandler rpc: " + ex.getMessage());
                        r = failure(payload, "500", "rpc failed: " + ex.getMessage());
                    } finally {
                        mine.decrementAndGet();
                    }
                    // revoked while the RPC ran: its reply is not given to the revoked credential
                    if (!gate.admit(ctx.channel(), principal)) return;
                    ctx.writeAndFlush(new TextWebSocketFrame(r));
                };
                try {
                    RPC_POOL.execute(task);
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    mine.decrementAndGet();
                    ctx.writeAndFlush(new TextWebSocketFrame(failure(payload, "503", "busy: wsapi has " + (RPC_THREADS + RPC_QUEUE)
                            + " requests in flight; retry shortly")));
                }
            } else {
                sendLater(ctx, request, frame.content().readableBytes());
            }
        } catch (Exception ex) {
            logger.error("ApiSocketWsHandler: " + ex.getMessage());
        }
    }

    private String globalRegion() {
        try {
            return plugin.getAgentService().getAgentState().getGlobalRegion();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The library's own RPC timeout, used when a request names none. */
    static final long DEFAULT_RPC_TIMEOUT_MS = 30_000L;
    /** Longest per-request RPC timeout a client may ask for (message_info.rpc_timeout_ms). */
    static final long MAX_RPC_TIMEOUT_MS = 3_600_000L;

    /** The client's requested RPC timeout, clamped to [1 s, 1 h]; 0 means the library default. */
    static long rpcTimeoutMs(Map<String, String> mi) {
        String v = mi == null ? null : mi.get("rpc_timeout_ms");
        if (v == null) return 0;
        try {
            return Math.max(1_000L, Math.min(MAX_RPC_TIMEOUT_MS, Long.parseLong(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A reply as sent to the client: its params, with any plugin config it carries redacted ({@link ReplyRedaction}) and
     * without the fabric's own stamps -- signatures, caller stamps, routing annotations, RPC plumbing ({@link InternalParams}).
     */
    static String success(MsgEvent response) {
        return gson.toJson(InternalParams.reply(ReplyRedaction.redact(response).getParams()));
    }

    /**
     * A failed RPC still answers, and carries the request's params back like any reply: a client that
     * correlates replies (pycrescolib's client_rpc_id) would otherwise discard it and wait out its own,
     * possibly much longer, timeout. Only small, non-secret params come back ({@link FailureEcho}): the
     * reply must fit a client frame and must not repeat a secret.
     */
    static String failure(Map<String, String> payload, String status, String desc) {
        Map<String, String> r = InternalParams.reply(FailureEcho.params(payload));
        desc = FailureEcho.redactText(desc);
        if (desc != null && desc.length() > FailureEcho.MAX_VALUE_BYTES) desc = desc.substring(0, FailureEcho.MAX_VALUE_BYTES) + "...";
        r.put("status", status);
        r.put("status_desc", desc);
        r.put("error", desc);
        return gson.toJson(r);
    }

    MsgEvent buildMsgEvent(Map<String, Map<String, String>> incoming) {
        MsgEvent request = null;
        try {
            Map<String, String> mi = incoming.get("message_info");
            Map<String, String> payload = incoming.get("message_payload");
            switch (mi.get("message_type")) {
                case "global_controller_msgevent": request = globalController(mi); break;
                case "global_agent_msgevent":      request = globalAgent(mi); break;
                case "global_plugin_msgevent":     request = globalPlugin(mi); break;
                case "kpi_msgevent":               request = plugin.getKPIMsgEvent(); break;
                case "regional_controller_msgevent": request = regionalController(mi); break;
                case "regional_agent_msgevent":    request = regionalAgent(mi); break;
                case "regional_plugin_msgevent":   request = regionalPlugin(mi); break;
                case "agent_msgevent":             request = plugin.getAgentMsgEvent(MsgEvent.Type.valueOf(mi.get("message_event_type"))); break;
                case "plugin_msgevent":            request = plugin.getPluginMsgEvent(MsgEvent.Type.valueOf(mi.get("message_event_type")), mi.get("dst_plugin")); break;
                default: logger.error("Unknown message type: " + mi.get("message_type"));
            }
            if (request != null && payload != null) {
                for (Map.Entry<String, String> e : payload.entrySet()) request.setParam(e.getKey(), e.getValue());
            }
        } catch (Exception ex) {
            logger.error("buildMsgEvent: " + ex.getMessage());
        }
        return request;
    }

    private MsgEvent globalController(Map<String, String> mi) {
        MsgEvent.Type t = MsgEvent.Type.valueOf(mi.get("message_event_type"));
        return (mi.containsKey("region_id") && mi.containsKey("agent_id"))
                ? plugin.getGlobalControllerMsgEvent(t, mi.get("region_id"), mi.get("agent_id"))
                : plugin.getGlobalControllerMsgEvent(t);
    }
    private MsgEvent globalAgent(Map<String, String> mi) {
        return plugin.getGlobalAgentMsgEvent(MsgEvent.Type.valueOf(mi.get("message_event_type")), mi.get("dst_region"), mi.get("dst_agent"));
    }
    private MsgEvent globalPlugin(Map<String, String> mi) {
        return plugin.getGlobalPluginMsgEvent(MsgEvent.Type.valueOf(mi.get("message_event_type")), mi.get("dst_region"), mi.get("dst_agent"), mi.get("dst_plugin"));
    }
    private MsgEvent regionalController(Map<String, String> mi) {
        MsgEvent.Type t = MsgEvent.Type.valueOf(mi.get("message_event_type"));
        return (mi.containsKey("region_id") && mi.containsKey("agent_id"))
                ? plugin.getRegionalControllerMsgEvent(t, mi.get("region_id"), mi.get("agent_id"))
                : plugin.getRegionalControllerMsgEvent(t);
    }
    private MsgEvent regionalAgent(Map<String, String> mi) {
        return plugin.getRegionalAgentMsgEvent(MsgEvent.Type.valueOf(mi.get("message_event_type")), mi.get("dst_agent"));
    }
    private MsgEvent regionalPlugin(Map<String, String> mi) {
        return plugin.getRegionalPluginMsgEvent(MsgEvent.Type.valueOf(mi.get("message_event_type")), mi.get("dst_agent"), mi.get("dst_plugin"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ApiSocketWsHandler error: " + cause.getMessage());
        ctx.close();
    }
}
