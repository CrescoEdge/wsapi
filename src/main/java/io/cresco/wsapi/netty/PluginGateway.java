package io.cresco.wsapi.netty;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.wsapi.auth.Authenticator;

/** Sends as the wsapi plugin itself (the controller then sees the plugin, not the client, as the caller). */
public final class PluginGateway implements ApiGateway {

    private final PluginBuilder plugin;

    public PluginGateway(PluginBuilder plugin) {
        this.plugin = plugin;
    }

    @Override
    public void send(MsgEvent msg, Authenticator.Principal caller) {
        plugin.msgOut(msg);
    }

    @Override
    public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
        return timeoutMs > 0 ? plugin.sendRPC(msg, timeoutMs) : plugin.sendRPC(msg);
    }
}
