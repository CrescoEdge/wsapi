package io.cresco.wsapi.netty;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSocketRpcTimeoutTest {

    private static final Gson GSON = new Gson();

    @SuppressWarnings("unchecked")
    private static Map<String, String> reply(Map<String, String> payload) {
        return GSON.fromJson(ApiSocketWsHandler.failure(payload, "504", "no reply"), Map.class);
    }

    private static String gzipB64(String s) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    @Test
    void timeoutIsClampedAndOptional() {
        assertEquals(0, ApiSocketWsHandler.rpcTimeoutMs(Map.of()));
        assertEquals(0, ApiSocketWsHandler.rpcTimeoutMs(null));
        assertEquals(0, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "soon")));
        assertEquals(1_000, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "5")));
        assertEquals(600_000, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "600000")));
        assertEquals(ApiSocketWsHandler.MAX_RPC_TIMEOUT_MS, ApiSocketWsHandler.rpcTimeoutMs(Map.of("rpc_timeout_ms", "999999999999")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureCarriesTheRequestParamsBack() {
        // the correlation id must come back, or a correlating client waits out its own timeout
        String r = ApiSocketWsHandler.failure(Map.of("action", "core.publish", "client_rpc_id", "abc123"), "504", "no reply");
        Map<String, String> m = GSON.fromJson(r, Map.class);
        assertEquals("abc123", m.get("client_rpc_id"));
        assertEquals("504", m.get("status"));
        assertEquals("core.publish", m.get("action"));
        assertTrue(m.get("status_desc").contains("no reply"));
        assertEquals("500", GSON.fromJson(ApiSocketWsHandler.failure(null, "500", "x"), Map.class).get("status"));
    }

    @Test
    void timedOutPluginUploadDoesNotEchoTheJar() {
        // the DGX case: a 1.8 MB jar echoed back closed pycrescolib's connection (1 MiB frame limit)
        byte[] jar = new byte[1_800_000];
        new java.util.Random(7).nextBytes(jar);
        String jardata = Base64.getEncoder().encodeToString(jar);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", "addplugin");
        p.put("jardata", jardata);
        p.put("client_rpc_id", "rpc-42");
        String r = ApiSocketWsHandler.failure(p, "504", "no reply within 30000 ms");
        assertTrue(r.length() < 2048, "reply is " + r.length() + " chars");
        Map<String, String> m = reply(p);
        assertEquals("rpc-42", m.get("client_rpc_id"));
        assertEquals("addplugin", m.get("action"));
        assertEquals("[omitted: data param, " + jardata.length() + " bytes]", m.get("jardata"));
    }

    @Test
    void compressedAndBinaryParamsBecomeMarkers() throws Exception {
        String small = gzipB64("{\"pluginname\":\"io.cresco.repo\"}");     // a small configparams: still data
        Map<String, String> p = new LinkedHashMap<>();
        p.put("configparams", small);
        p.put("blob", Base64.getEncoder().encodeToString(new byte[300]));   // raw base64 data param
        p.put("region_name", "global-region");
        Map<String, String> m = reply(p);
        assertEquals("[omitted: data param, " + small.length() + " bytes]", m.get("configparams"));
        assertTrue(m.get("blob").startsWith("[omitted: data param, "));
        assertEquals("global-region", m.get("region_name"));
    }

    @Test
    void valuesAreCappedEachAndInTotal() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("desc", "x ".repeat(1000));                                    // 2000 bytes, not base64
        for (int i = 0; i < 40; i++) p.put("k" + i, "v-" + "y".repeat(900) + i);   // 40 x ~900 B = 36 KB
        p.put("client_rpc_id", "last-in-request");                            // must survive the total cap
        String r = ApiSocketWsHandler.failure(p, "500", "rpc failed");
        Map<String, String> m = reply(p);
        assertEquals("[omitted: 2000 bytes, over the 1024-byte echo cap]", m.get("desc"));
        assertEquals("last-in-request", m.get("client_rpc_id"));
        assertTrue(r.length() <= FailureEcho.MAX_TOTAL_BYTES + 1024, "reply is " + r.length() + " chars");
        assertTrue(m.get("echo_omitted").contains("param(s) past the 16384-byte echo cap"));
        assertEquals("v-" + "y".repeat(900) + "0", m.get("k0"));             // early small params are kept whole
    }

    @Test
    void secretNamedParamsAreNeverEchoed() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_rpc_id", "c1");
        p.put("password", "pw-1111");
        p.put("keyStorePassword", "pw-2222");
        p.put("api_token", "pw-3333");
        p.put("service_key", "pw-4444");
        p.put("hsmPin", "pw-5555");
        p.put("HSM_PIN", "pw-6666");
        p.put("ping_interval", "5000");                                       // not a secret (ConfigRedaction rule)
        p.put("mapping", "a:b");
        p.put("broker_uri", "nio+ssl://h:32010?keyStorePassword=pw-7777&tcpNoDelay=true");
        String r = ApiSocketWsHandler.failure(p, "500", "rpc failed: connect nio+ssl://h?trustStorePassword=pw-8888");
        for (int i = 1; i <= 8; i++) assertFalse(r.contains("pw-" + String.valueOf(i).repeat(4)), "leaked pw-" + i + ": " + r);
        Map<String, String> m = reply(p);
        assertNull(m.get("password"));
        assertFalse(m.containsKey("keyStorePassword"));
        assertFalse(m.containsKey("hsmPin"));
        assertEquals("5000", m.get("ping_interval"));
        assertEquals("a:b", m.get("mapping"));
        assertEquals("nio+ssl://h:32010?keyStorePassword=[REDACTED]&tcpNoDelay=true", m.get("broker_uri"));
        assertEquals("rpc failed: connect nio+ssl://h?trustStorePassword=[REDACTED]", GSON.fromJson(r, Map.class).get("status_desc"));
    }

    @Test
    void secretRuleMatchesTheController() {
        for (String k : new String[]{"password", "db_password", "secret", "token", "passphrase", "service_key", "pin",
                "HSM_PIN", "pkcs11.pin", "hsmPin", "userPIN", "pinCode"})
            assertTrue(FailureEcho.isSecretKey(k), k);
        for (String k : new String[]{"ping_interval", "mapping", "spinlock", "pinned", "action", "client_rpc_id", "keyname"})
            assertFalse(FailureEcho.isSecretKey(k), k);
    }
}
