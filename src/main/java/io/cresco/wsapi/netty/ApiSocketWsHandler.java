package io.cresco.wsapi.netty;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Netty port of {@code APISocket} — MsgEvent RPC/emit bridge. Text frame carries
 * {@code {"message_info":{...},"message_payload":{...}}}; builds the MsgEvent for the requested
 * type, then either {@code sendRPC} (reply with the response params as JSON) or {@code msgOut}.
 */
public class ApiSocketWsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Gson gson = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, String>>>() {}.getType();

    private final PluginBuilder plugin;
    private final CLogger logger;

    public ApiSocketWsHandler(PluginBuilder plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(ApiSocketWsHandler.class.getName(), CLogger.Level.Info);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame)) return;
        try {
            String message = ((TextWebSocketFrame) frame).text();
            Map<String, Map<String, String>> incoming = gson.fromJson(message, TYPE);
            MsgEvent request = buildMsgEvent(incoming);
            boolean isRPC = Boolean.parseBoolean(incoming.get("message_info").get("is_rpc"));
            if (isRPC) {
                MsgEvent response = plugin.sendRPC(request);
                String r = (response == null) ? "{\"error\":\"Cresco rpc response was null\"}"
                                               : gson.toJson(response.getParams());
                ctx.writeAndFlush(new TextWebSocketFrame(r));
            } else {
                plugin.msgOut(request);
            }
        } catch (Exception ex) {
            logger.error("ApiSocketWsHandler: " + ex.getMessage());
        }
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
