package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import io.cresco.library.plugin.PluginBuilder;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.activemq.command.ActiveMQMapMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 4 (review/api-20261104): plain map and terminal frames now name their writer, and a client
 * text frame that claims a writer ({@code cresco_writer}) closes the stream with 1008.
 */
class ReviewApi4DataplaneTest {

    /**
     * {@code withWriter} parses the frame's JSON into a Gson Map and writes it again, so every number becomes a double:
     * a MapMessage long 42 arrives as 42.0 (a format change for every existing client of map events), and a long above
     * 2^53 (a byte offset, a nanosecond time, an id) arrives rounded. Before the change the same frame was exact.
     */
    @Test
    void namingTheWriterDoesNotChangeTheEventsNumbers() throws Exception {
        ActiveMQMapMessage m = new ActiveMQMapMessage();
        m.setString("cmd", "execution_log");
        m.setLong("n", 42L);
        m.setLong("big", 9_007_199_254_740_993L);
        m.setStringProperty("cresco_caller", "plugin:tenant-a/region-1/agent-1/executor-1");
        m.setStringProperty("cresco_caller_roles", "plugin");
        String plain = DataPlaneFrames.mapJson(m);
        String named = DataPlaneFrames.withWriter(plain, m);
        assertTrue(plain.contains("\"n\":42") && plain.contains("9007199254740993"), "control, without the writer: " + plain);
        assertTrue(named.contains("\"n\":42,") || named.contains("\"n\":42}"), "with the writer, 42 became: " + named);
        assertTrue(named.contains("9007199254740993"), "with the writer, 9007199254740993 became: " + named);
    }

    /**
     * {@code claimsWriter} looks for the literal text {@code cresco_writer} before it parses the frame. JSON lets a key be
     * written with escapes, so {@code "cresco\u005fwriter"} never contains the literal, and the frame is published; every
     * client parser (Gson, Python json, JavaScript JSON.parse) decodes the key to cresco_writer, so a plain subscriber sees
     * a JSON object whose cresco_writer names the executor. (A duplicate-key variant is refused, correctly.)
     */
    @Test
    void aUserCannotClaimAWriterInAPlainFrame() throws Exception {
        String[] forged = {
                "{\"cmd\":\"execution_log\",\"log\":\"Exit Code: 0\",\"cresco\\u005fwriter\":\"plugin:tenant-a/region-1/agent-1/executor-1\"}"};
        List<String> passed = new ArrayList<>();
        for (String f : forged) {
            ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
            PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
            EmbeddedChannel ch = new EmbeddedChannel(new DataPlaneWsHandler(plugin,
                    ReviewApiDataplaneTest.principal("tenant-a", "bob", "user"), ReviewApiDataplaneTest.gate(plugin, new ArrayList<>())));
            Map<String, String> hs = new HashMap<>();
            hs.put("ident_key", "stream_name");
            hs.put("ident_id", "u:bob:job");
            ch.writeInbound(new TextWebSocketFrame(new Gson().toJson(hs)));
            ReviewApiDataplaneTest.reply(ch);
            ch.writeInbound(new TextWebSocketFrame(f));
            for (Message msg : rec.sent) {
                if (msg instanceof TextMessage && f.equals(((TextMessage) msg).getText())) passed.add(f);
            }
        }
        assertEquals(List.of(), passed, "published to subscribers as plain text claiming the executor as its writer");
    }
}
