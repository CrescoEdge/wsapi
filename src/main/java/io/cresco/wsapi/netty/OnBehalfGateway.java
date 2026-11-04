package io.cresco.wsapi.netty;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.security.Caller;
import io.cresco.wsapi.auth.Authenticator;

import java.util.Collections;

/**
 * Sends each API client's messages on behalf of that client ({@link PluginBuilder#msgOutOnBehalfOf}): the controller
 * stamps the verified caller {@code api:<tenant>/<user>} with the client's role ({@code admin} or {@code user}), and
 * every receiver authorizes the action against it. The controller accepts this only from {@code on_behalf_bundles}
 * (default {@code io.cresco.wsapi}); otherwise it throws {@link SecurityException} and nothing is sent.
 */
public final class OnBehalfGateway implements ApiGateway {

    private final PluginBuilder plugin;

    public OnBehalfGateway(PluginBuilder plugin) {
        this.plugin = plugin;
    }

    /** The library caller for an authenticated client. */
    static Caller callerOf(Authenticator.Principal p) {
        return Caller.api(p.tenant, p.user, Collections.singleton(p.role));
    }

    @Override
    public void send(MsgEvent msg, Authenticator.Principal caller) {
        plugin.msgOutOnBehalfOf(msg, callerOf(caller));
    }

    @Override
    public MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller) {
        Caller c = callerOf(caller);
        return timeoutMs > 0 ? plugin.sendRPCOnBehalfOf(msg, timeoutMs, c) : plugin.sendRPCOnBehalfOf(msg, c);
    }
}
