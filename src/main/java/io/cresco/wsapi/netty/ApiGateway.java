package io.cresco.wsapi.netty;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.wsapi.auth.Authenticator;

/**
 * How wsapi sends a message for an authenticated API client. Every message an API client causes (API socket requests,
 * log-stream configuration) goes through here with that client as the caller.
 */
public interface ApiGateway {

    /** Send {@code msg} for {@code caller}, without waiting for a reply. */
    void send(MsgEvent msg, Authenticator.Principal caller);

    /**
     * Send {@code msg} for {@code caller} and wait for the reply.
     *
     * @param timeoutMs how long to wait; 0 or less means the library default
     * @return the reply, or null if none arrived in time
     */
    MsgEvent rpc(MsgEvent msg, long timeoutMs, Authenticator.Principal caller);
}
