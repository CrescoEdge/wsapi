package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.plugin.PluginBuilder;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.buffer.Unpooled;
import jakarta.jms.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 2 (review/api-20261104): stream ownership on the dataplane. A user owns
 * {@code u:<user>:...} streams; the handshake checks ident_key/ident_id, but wsapi then stamps BOTH ident_key=ident_id
 * and io_type_key=input_id on every message, in that order. io_type_key may be any non-reserved identifier, including
 * {@code stream_name} itself, so the second stamp replaces the owned stream name with any stream the user likes.
 */
class ReviewApi2DataplaneTest {

    @Test
    void aUserCannotWriteIntoAStreamItDoesNotOwn() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin,
                ReviewApiDataplaneTest.principal("tenant-a", "bob", "user"), ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
        Map<String, String> hs = new HashMap<>();
        hs.put("ident_key", "stream_name");
        hs.put("ident_id", "u:bob:mine");                   // owned: the handshake passes
        hs.put("io_type_key", "stream_name");               // ... and this is stamped second
        hs.put("input_id", "u:alice:private");              // alice's own stream
        ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(hs)));
        Map<String, String> reply = ReviewApiDataplaneTest.reply(ch);
        ch.writeInbound(new TextWebSocketFrame("{\"cmd\":\"execution_log\",\"log\":\"Exit Code: 0\"}"));
        ch.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{1, 2, 3})));
        List<String> landedIn = new ArrayList<>();
        for (Message m : rec.sent) landedIn.add(m.getStringProperty("stream_name"));
        assertFalse(landedIn.contains("u:alice:private"),
                "bob (role user) wrote into alice's stream: activation " + reply + ", messages landed in " + landedIn);
    }
}
