package io.cresco.wsapi.netty;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** wsapi.dataplane.bytes counts bytes on the wire, not characters. */
class DataPlaneBytesTest {

    @Test
    void textFramesAreCountedInUtf8Bytes() {
        TextWebSocketFrame ascii = new TextWebSocketFrame("hello");
        TextWebSocketFrame multi = new TextWebSocketFrame("héllo 世界 😀");   // é (2) 世界 (3+3) 😀 (4)
        try {
            assertEquals(5, DataPlaneWsHandler.utf8Bytes(ascii));
            assertEquals("héllo 世界 😀".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    DataPlaneWsHandler.utf8Bytes(multi));
            assertEquals(18, DataPlaneWsHandler.utf8Bytes(multi));   // 1+2+3+1+6+1+4; the old count was 11 characters
        } finally {
            ascii.release();
            multi.release();
        }
    }
}
