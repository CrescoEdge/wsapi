package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.activemq.command.ActiveMQMapMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A client's frame claims a writer when any parser would decode a top-level key cresco_writer from it, however the key is
 * spelled; numbers keep their text when wsapi names the writer of a map event, in plain and envelope frames.
 */
class WriterClaimTest {

    static final String BS = "\\";

    @Test
    void everySpellingOfTheKeyIsAClaim() {
        String[] claims = {
                "{\"cresco" + BS + "u005fwriter\":\"plugin:x\"}",
                "{\"" + BS + "u0063resco_writer\":\"plugin:x\"}",
                "{\"" + BS + "u0063" + BS + "u0072" + BS + "u0065" + BS + "u0073" + BS + "u0063" + BS + "u006f" + BS + "u005f" + BS
                        + "u0077" + BS + "u0072" + BS + "u0069" + BS + "u0074" + BS + "u0065" + BS + "u0072\":\"plugin:x\"}",
                "{\"cmd\":\"execution_log\",\"cresco_writer\":\"plugin:x\",\"cresco_writer\":\"api:t/bob\"}",   // duplicate keys
                "{\"cresco_writer\":\"api:t/bob\",\"cresco_writer\":\"plugin:x\"}",
                "{'cresco_writer':'plugin:x'}",                                                              // Gson-lenient forms
                "{cresco_writer:\"plugin:x\"}",
                "{/* c */ \"cresco_writer\" : \"plugin:x\"}",
                "﻿  {\"cresco_writer\":\"plugin:x\"}",
                "\n\t {\"cresco_writer_roles\":\"plugin\"}",
                "{\"CRESCO_WRITER\":\"plugin:x\"}",
                "{\"log\":\"Exit Code: 0\",\"cresco_writer\":\"plugin:x\"} trailing",
                "{\"a\":[1,{\"b\":[2,3]}],\"n\":1.5e3,\"x\":NaN,\"cresco" + BS + "u005Fwriter\":\"plugin:x\"}",
        };
        for (String c : claims) assertTrue(DataPlaneFrames.claimsWriter(c), c);
    }

    @Test
    void whatNoParserReadsAsATopLevelKeyIsNoClaim() {
        String[] fine = {
                "{\"cmd\":\"execution_log\",\"log\":\"Exit Code: 0\"}",
                "{\"note\":\"cresco_writer\"}",
                "{\"a\":{\"cresco_writer\":\"plugin:x\"}}",
                "[{\"cresco_writer\":\"plugin:x\"}]",
                "the word cresco_writer in a line",
                "\"{\\\"cresco_writer\\\":\\\"x\\\"}\"",
                "{\"a\": }, \"cresco_writer\": \"plugin:x\"}",
                "",
        };
        for (String f : fine) assertFalse(DataPlaneFrames.claimsWriter(f), f);
    }

    @Test
    void aDeeplyNestedFrameIsCheckedWithoutParsingItWhenItCannotNameTheKeyAndRefusedWhenItMight() {
        int depth = 200_000;
        String deep = "{\"a\":" + "[".repeat(depth) + "]".repeat(depth) + "}";
        assertFalse(DataPlaneFrames.claimsWriter(deep), "no key anywhere: no claim (and nothing parsed)");
        String forged = "{\"a\":" + "[".repeat(depth) + "]".repeat(depth) + ",\"cresco" + BS + "u005fwriter\":\"plugin:x\"}";
        assertTrue(DataPlaneFrames.claimsWriter(forged), "nested beyond the checked depth and naming the key: refused");
        String shallow = "{\"a\":" + "[".repeat(100) + "]".repeat(100) + ",\"cresco_writer\":\"plugin:x\"}";
        assertTrue(DataPlaneFrames.claimsWriter(shallow));
    }

    @Test
    void envelopeMapFramesKeepTheirNumbersToo() throws Exception {
        ActiveMQMapMessage m = new ActiveMQMapMessage();
        m.setString("cmd", "execution_log");
        m.setLong("n", 42L);
        m.setLong("big", 9_007_199_254_740_993L);
        m.setDouble("d", 0.5);
        m.setStringProperty("cresco_caller", "plugin:tenant-a/region-1/agent-1/executor-1");
        m.setStringProperty("cresco_caller_roles", "plugin");
        JsonObject env = new Gson().fromJson(DataPlaneFrames.envelopeJson(m), JsonObject.class);
        JsonObject map = env.getAsJsonObject("map");
        assertEquals("42", map.get("n").getAsString());
        assertEquals("9007199254740993", map.get("big").getAsString());
        assertEquals("0.5", map.get("d").getAsString());
        assertEquals("plugin:tenant-a/region-1/agent-1/executor-1", env.get("writer").getAsString());
        String plain = DataPlaneFrames.withWriter(DataPlaneFrames.mapJson(m), m);
        JsonObject p = new Gson().fromJson(plain, JsonObject.class);
        assertEquals("42", p.get("n").getAsString());
        assertEquals("9007199254740993", p.get("big").getAsString());
        assertEquals("plugin", p.get("cresco_writer_roles").getAsString());
    }
}
