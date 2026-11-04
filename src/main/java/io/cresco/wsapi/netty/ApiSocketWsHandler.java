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
 * JSON) or fire-and-forget.
 *
 * <p>Before anything is sent: the reserved caller parameters ({@code cresco_caller}, {@code cresco_caller_tenant},
 * {@code cresco_caller_roles}) are removed from the client's payload (only the controller sets them), and a message
 * that names another tenant ({@code cresco_tenant}) is refused unless the client is an admin of a superuser tenant.
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
    private static final ThreadPoolExecutor RPC_POOL = new ThreadPoolExecutor(
            RPC_THREADS, RPC_THREADS, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(RPC_QUEUE),
            r -> {
                Thread t = new Thread(r, "wsapi-apisocket-rpc");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    static {
        RPC_POOL.allowCoreThreadTimeOut(true);
    }

    /** RPCs each caller has in flight (all of the caller's connections together). */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Default in-flight RPC limits and timeout caps, by role. */
    static final int DEFAULT_MAX_INFLIGHT_USER = 8;
    static final int DEFAULT_MAX_INFLIGHT_ADMIN = 32;
    static final long DEFAULT_MAX_TIMEOUT_USER_MS = 120_000L;

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
        String target = payload.get(TenantNamespace.TENANT_PARAM);
        if (target != null && !auth.mayTarget(caller, target)) {
            return caller + " may not address tenant " + target;
        }
        return null;
    }

    /**
     * For a {@code user}, the region a message is addressed to must be this agent's own: an agent joins only a region
     * controller of its own tenant, so this agent's region is the one region known to be the user's tenant (the
     * controller does not tell wsapi the tenant of other regions). Admins are not limited here; the controller authorizes
     * every message again.
     *
     * @return null when the destination is allowed, else why not
     */
    static String destinationRefusal(Map<String, String> mi, Authenticator.Principal caller, String localRegion, String globalRegion) {
        if (caller == null || caller.isAdmin()) return null;
        if (mi == null) return "no message_info";
        String type = mi.get("message_type");
        String region;
        if (type == null) return "no message_type";
        switch (type) {
            case "global_controller_msgevent":   region = mi.containsKey("region_id") ? mi.get("region_id") : globalRegion; break;
            case "global_agent_msgevent":
            case "global_plugin_msgevent":       region = mi.get("dst_region"); break;
            case "regional_controller_msgevent": region = mi.containsKey("region_id") ? mi.get("region_id") : localRegion; break;
            case "regional_agent_msgevent":
            case "regional_plugin_msgevent":
            case "kpi_msgevent":
            case "agent_msgevent":
            case "plugin_msgevent":              region = localRegion; break;
            default: return "unknown message_type";
        }
        if (region == null || localRegion == null || !region.equals(localRegion)) {
            return caller + " may only address its own tenant's region " + localRegion + ", not " + region
                    + " (no other region is known to be in tenant " + caller.tenant + ")";
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
        try {
            String message = ((TextWebSocketFrame) frame).text();
            Map<String, Map<String, String>> incoming = gson.fromJson(message, TYPE);
            Map<String, String> mi = incoming == null ? null : incoming.get("message_info");
            Map<String, String> payload = incoming == null ? null : incoming.get("message_payload");
            boolean isRPC = mi != null && Boolean.parseBoolean(mi.get("is_rpc"));
            String refusal = vet(payload, principal, gate == null ? null : gate.authenticator);
            if (refusal == null) {
                refusal = destinationRefusal(mi, principal, plugin.getRegion(), globalRegion());
            }
            if (refusal != null) {
                logger.warn("wsapi refused a message from " + principal + ": " + refusal);
                if (isRPC) ctx.writeAndFlush(new TextWebSocketFrame(failure(payload, "403", "unauthorized: " + refusal)));
                return;
            }
            MsgEvent request = buildMsgEvent(incoming);
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
                gate.gateway.send(request, principal);
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

    /** A reply as sent to the client: its params, with any plugin config it carries redacted ({@link ReplyRedaction}). */
    static String success(MsgEvent response) {
        return gson.toJson(ReplyRedaction.redact(response).getParams());
    }

    /**
     * A failed RPC still answers, and carries the request's params back like any reply: a client that
     * correlates replies (pycrescolib's client_rpc_id) would otherwise discard it and wait out its own,
     * possibly much longer, timeout. Only small, non-secret params come back ({@link FailureEcho}): the
     * reply must fit a client frame and must not repeat a secret.
     */
    static String failure(Map<String, String> payload, String status, String desc) {
        Map<String, String> r = FailureEcho.params(payload);
        desc = FailureEcho.redactText(desc);
        if (desc != null && desc.length() > FailureEcho.MAX_VALUE_BYTES) desc = desc.substring(0, FailureEcho.MAX_VALUE_BYTES) + "...";
        r.put("status", status);
        r.put("status_desc", desc);
        r.put("error", desc);
        return gson.toJson(r);
    }

    private MsgEvent buildMsgEvent(Map<String, Map<String, String>> incoming) {
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
