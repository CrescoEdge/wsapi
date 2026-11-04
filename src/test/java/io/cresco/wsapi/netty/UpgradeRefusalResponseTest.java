package io.cresco.wsapi.netty;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A refused upgrade carries its reason in the body and, when it is short printable ASCII, in the status line too. */
class UpgradeRefusalResponseTest {

    @Test
    void theReasonIsTheBodyAndTheReasonPhrase() {
        FullHttpResponse r = WsRouter.response(HttpResponseStatus.TOO_MANY_REQUESTS, "too many open connections for this user; close some first");
        try {
            assertEquals(429, r.status().code());
            assertEquals("too many open connections for this user; close some first", r.status().reasonPhrase());
            assertEquals("too many open connections for this user; close some first", r.content().toString(CharsetUtil.UTF_8));
        } finally {
            r.release();
        }
    }

    @Test
    void aReasonUnfitForAStatusLineStaysInTheBodyOnly() {
        for (String body : new String[]{"line\r\nX-Injected: 1", "café", "x".repeat(201), ""}) {
            FullHttpResponse r = WsRouter.response(HttpResponseStatus.UNAUTHORIZED, body);
            try {
                assertEquals("Unauthorized", r.status().reasonPhrase(), body);
                assertEquals(body, r.content().toString(CharsetUtil.UTF_8));
            } finally {
                r.release();
            }
        }
    }
}
