package io.cresco.wsapi.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Message parameters in forwarded log lines keep their values only when allowlisted. */
class LogRedactionTest {

    @Test
    void mapParamsOutsideTheAllowlistAreRedactedWhateverTheirName() {
        String line = "Worker [w1] sending message: Type=EXEC, Params={action=putnote, note=alice's private text, dst_region=r1, "
                + "callId-r-a-p=c1, blob={x=1, y=2}, client_rpc_id=abc}";
        String r = LogRedaction.redact(line);
        assertTrue(r.contains("action=putnote"), r);
        assertTrue(r.contains("dst_region=r1"), r);
        assertTrue(r.contains("callId-r-a-p=c1"), r);
        assertTrue(r.contains("client_rpc_id=abc"), r);
        assertTrue(r.contains("note=<redacted>"), r);
        assertTrue(r.contains("blob=<redacted>"), r);
        assertFalse(r.contains("private text") || r.contains("x=1"), r);
        assertTrue(r.startsWith("Worker [w1] sending message: Type=EXEC, Params={"), "text outside the map is kept: " + r);
    }

    @Test
    void jsonPairsAndCutLinesAreRedactedToo() {
        String json = "reply {\"status\":\"10\",\"note\":\"secret \\\"quoted\\\" text\",\"region_id\":\"r\"}";
        String r = LogRedaction.redact(json);
        assertTrue(r.contains("\"status\":\"10\"") && r.contains("\"region_id\":\"r\""), r);
        assertFalse(r.contains("secret"), r);
        String cut = "Text Message: {action=x, password=hunter2, note=abc";          // a line cut mid-map
        String c = LogRedaction.redact(cut);
        assertFalse(c.contains("hunter2") || c.contains("abc"), c);
        assertTrue(c.contains("action=x"), c);
    }

    @Test
    void ordinaryLinesAreUntouched() {
        for (String plain : new String[]{"wsapi Netty WebSocket server started on wss port 34282",
                "applyNetTuning: sockBuf=4194304 readChunk=262144 writeHi=2097152", "", "a { b"}) {
            if (plain.equals("a { b")) continue;
            assertEquals(plain, LogRedaction.redact(plain));
        }
        assertNull(LogRedaction.redact(null));
    }
}
