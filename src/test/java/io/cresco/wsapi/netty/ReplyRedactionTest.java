package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.cresco.library.messaging.MsgEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An API socket reply never carries a plugin config's secret values back to the client (GaiaKeep OUT-03). */
class ReplyRedactionTest {

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type MAP = new TypeToken<Map<String, Object>>() {}.getType();
    private static final String CONFIG = "{\"pluginname\":\"io.cresco.gfs\",\"gfs_secret\":\"s3cr3t-1\",\"hsmPin\":1234,"
            + "\"gfs_roles\":\"index\",\"broker_uri\":\"nio+ssl://h:1?keyStorePassword=s3cr3t-2\","
            + "\"nested\":\"{\\\"password\\\":\\\"s3cr3t-3\\\",\\\"k\\\":\\\"v\\\"}\"}";

    private static MsgEvent reply() {
        MsgEvent r = new MsgEvent(MsgEvent.Type.CONFIG, "r1", "a1", null, "r1", "a2", null, true, true);
        r.setParam("action", "pluginadd");
        r.setParam("status_code", "10");
        r.setParam("pluginid", "plugin/3");
        return r;
    }

    private static void assertRedacted(String configJson) {
        assertFalse(configJson.contains("s3cr3t") || configJson.contains("1234"), configJson);
        Map<String, Object> c = GSON.fromJson(configJson, MAP);
        assertEquals(FailureEcho.REDACTED, c.get("gfs_secret"));
        assertEquals(FailureEcho.REDACTED, c.get("hsmPin"));
        assertEquals("index", c.get("gfs_roles"), "non-secret settings stay");
        assertEquals("nio+ssl://h:1?keyStorePassword=" + FailureEcho.REDACTED, c.get("broker_uri"));
        Map<String, Object> nested = GSON.fromJson((String) c.get("nested"), MAP);
        assertEquals(FailureEcho.REDACTED, nested.get("password"));
        assertEquals("v", nested.get("k"));
    }

    @Test
    void aCompressedConfigInAReplyIsRedactedAndStaysCompressed() {
        MsgEvent r = reply();
        r.setCompressedParam("configparams", CONFIG);
        Map<String, String> sent = GSON.fromJson(ApiSocketWsHandler.success(r), new TypeToken<Map<String, String>>() {}.getType());
        assertTrue(sent.get("configparams").startsWith("H4sI"), "the client still gets a compressed param");
        MsgEvent back = new MsgEvent(MsgEvent.Type.CONFIG, null, null, null, null, null, null, true, true);
        back.setParam("configparams", sent.get("configparams"));
        assertRedacted(back.getCompressedParam("configparams"));
        assertEquals("10", sent.get("status_code"));
        assertEquals("plugin/3", sent.get("pluginid"));
    }

    @Test
    void aPlainConfigInAReplyIsRedacted() {
        MsgEvent r = reply();
        r.setParam("configparams", CONFIG);
        Map<String, String> sent = GSON.fromJson(ApiSocketWsHandler.success(r), new TypeToken<Map<String, String>>() {}.getType());
        assertRedacted(sent.get("configparams"));
    }

    @Test
    void anUnreadableConfigIsWithheldAndOtherRepliesPassThrough() {
        MsgEvent r = reply();
        r.setParam("configparams", "password=s3cr3t-4 not json");
        assertEquals("{}", ReplyRedaction.redact(r).getParam("configparams"));
        MsgEvent bad = reply();
        bad.setParam("configparams", "H4sIthis-is-not-gzip");
        assertEquals("{}", ReplyRedaction.redact(bad).getCompressedParam("configparams"));

        MsgEvent plain = reply();
        Map<String, String> before = new LinkedHashMap<>(plain.getParams());
        assertEquals(before, ReplyRedaction.redact(plain).getParams(), "a reply without plugin config is untouched");
        assertNull(ReplyRedaction.redact(null));
    }

    @Test
    void aFailureEchoOfAPlainConfigHasNoSecret() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", "pluginadd");
        p.put("client_rpc_id", "rpc-7");
        p.put("configparams", CONFIG);
        String r = ApiSocketWsHandler.failure(p, "504", "no reply");
        assertFalse(r.contains("s3cr3t") || r.contains("1234"), r);
        Map<String, String> m = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
        assertRedacted(m.get("configparams"));
        assertEquals("rpc-7", m.get("client_rpc_id"));
    }
}
