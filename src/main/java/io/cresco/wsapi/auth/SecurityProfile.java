package io.cresco.wsapi.auth;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The security profile ({@code security_profile}, the setting the controller reads too). {@code production}: API clients
 * authenticate with client certificates only (mutual TLS on the API socket, the dataplane and the log stream, chaining to
 * {@code wsapi_client_ca}); passwords and the service key are refused, and API tokens too unless
 * {@code wsapi_allow_tokens=true}. Anything else is the debug profile: everything as before.
 */
public final class SecurityProfile {

    /** The setting's name, shared with the controller. */
    public static final String SETTING = "security_profile";
    public static final String PRODUCTION = "production";

    private SecurityProfile() {}

    public static boolean isProduction(String value) {
        return value != null && PRODUCTION.equalsIgnoreCase(value.trim());
    }

    /** What production needs, checked before wsapi starts: the client certificates, or why it must not start. */
    public static final class Production {
        public final ClientCertificates certificates;
        public final List<String> problems;

        Production(ClientCertificates certificates, List<String> problems) {
            this.certificates = certificates;
            this.problems = List.copyOf(problems);
        }
    }

    /**
     * Check the production settings: the client CA bundle must load, a configured deny list or CRL must load (and verify),
     * and no disabled method may be configured on (a service key that is set and not disabled). Empty problems: start.
     */
    public static Production check(String clientCa, String denyFile, String crlFile, String serviceKey, boolean serviceKeyEnabled,
                                   LongSupplier clock) {
        List<String> problems = new ArrayList<>();
        ClientCertificates certs = null;
        try {
            certs = new ClientCertificates(clientCa == null || clientCa.isBlank() ? null : Paths.get(clientCa.trim()),
                    path(denyFile), path(crlFile), clock);
            String p = certs.problem();
            if (p != null) problems.add(p);
        } catch (ClientCertificates.Invalid e) {
            problems.add(e.getMessage());
        }
        if (serviceKey != null && !serviceKey.isEmpty() && serviceKeyEnabled) {
            problems.add("the service key is configured (cresco_service_key) and enabled, and production accepts client certificates "
                    + "only: remove it, or set wsapi_service_key_enabled=false");
        }
        return new Production(problems.isEmpty() ? certs : null, problems);
    }

    private static Path path(String s) {
        return s == null || s.isBlank() ? null : Paths.get(s.trim());
    }
}
