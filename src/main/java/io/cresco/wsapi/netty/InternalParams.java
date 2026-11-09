package io.cresco.wsapi.netty;

import io.cresco.library.security.Caller;
import io.cresco.library.security.TenantNamespace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fabric's own stamps never reach an API client, and an API client never writes them.
 *
 * <p>A reply arrives at wsapi as the fabric delivered it: with the end-to-end signature of its origin
 * ({@code cresco_sig}, {@code cresco_sig_by}, {@code cresco_sig_ts}, {@code cresco_sig_cred} -- a node's whole certificate
 * or attestation -- and the signed caller copy {@code cresco_sig_caller*}), the broker's caller stamp
 * ({@code cresco_caller*}, the alias included), the namespace tenant the origin signed ({@code cresco_tenant}), the
 * routers' annotations ({@code routepath-*}, {@code linkcost-*}, {@code srcroute*}) and the library's RPC plumbing
 * ({@code callId-*}, {@code is_rpc}). None of it is the responder's answer: it is removed before a reply (or a failure's
 * echo of the request) is sent to the client. A client's own correlation parameters ({@code client_rpc_id} and any other
 * request parameter a responder echoes) are kept.
 *
 * <p>The same names, except {@code cresco_tenant} (a client's addressed tenant, vetted against its role) and
 * {@code is_rpc}, are removed from what a client sends: only infrastructure writes them.
 *
 * <p>Dataplane envelope frames: every {@code cresco_*} property is infrastructure's, and is removed from
 * {@code properties}, except the stream's sequence numbers {@code cresco_seq} and {@code cresco_gap}, which are the
 * consumer's documented way to see a loss (caller-authorization.md section 8). The verified writer is given in the
 * envelope's own {@code writer}, {@code writer_roles} and {@code writer_tenant} fields.
 */
final class InternalParams {

    /** Exact reply parameter names infrastructure writes (beside the prefixes below). */
    static final Set<String> REPLY_EXACT = Set.of(TenantNamespace.TENANT_PARAM, "is_rpc");

    /** Prefixes of parameter names only infrastructure writes, in replies and requests alike. */
    static final List<String> PREFIXES = List.of("cresco_sig", "cresco_caller", "routepath-", "linkcost-", "srcroute", "callId-");

    /** Dataplane properties a client keeps although they are infrastructure's: the stream's loss detection. */
    static final Set<String> DATAPLANE_KEPT = Set.of("cresco_seq", "cresco_gap");

    private InternalParams() {}

    private static boolean prefixed(String key) {
        for (String p : PREFIXES) if (key.startsWith(p)) return true;
        return false;
    }

    /** Is a reply parameter named {@code key} infrastructure's (never sent to an API client)? */
    static boolean internalReply(String key) {
        return key == null || REPLY_EXACT.contains(key) || prefixed(key) || Caller.RESERVED_PARAMS.contains(key);
    }

    /** Is a request parameter named {@code key} one only infrastructure writes (dropped from a client's request)? */
    static boolean internalRequest(String key) {
        return key == null || prefixed(key) || Caller.RESERVED_PARAMS.contains(key);
    }

    /** Is a dataplane property named {@code key} infrastructure's (left out of an envelope's properties)? */
    static boolean internalProperty(String key) {
        return key == null || key.startsWith("JMS") || (key.startsWith("cresco_") && !DATAPLANE_KEPT.contains(key));
    }

    /** A copy of a reply's params without infrastructure's. */
    static Map<String, String> reply(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) return out;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!internalReply(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Removes, in place, what a client may not write into a request. */
    static void stripRequest(Map<String, String> payload) {
        if (payload != null) payload.keySet().removeIf(InternalParams::internalRequest);
    }
}
