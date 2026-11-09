package io.cresco.wsapi.auth;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The security profile ({@code security_profile}): the node's, as its controller answers it ({@code
 * PluginBuilder.getSecurityProfile()}); wsapi's own setting can only confirm it ({@link #conflict}). {@code production}: API clients
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

    /**
     * Why wsapi must not start: its own {@code security_profile} names a profile other than the node's (the controller's
     * answer, {@code PluginBuilder.getSecurityProfile()}), or one that is not a profile; null when it is unset or agrees.
     * The node's profile decides; this setting can only confirm it.
     */
    public static String conflict(String ownSetting, io.cresco.library.security.SecurityProfile node) {
        if (ownSetting == null || ownSetting.isBlank()) return null;
        io.cresco.library.security.SecurityProfile own = io.cresco.library.security.SecurityProfile.parse(ownSetting);
        if (own == null) return "security_profile=" + ownSetting.trim() + " in wsapi's configuration is not a profile (production or standard)";
        io.cresco.library.security.SecurityProfile effective = node == io.cresco.library.security.SecurityProfile.INSECURE
                ? io.cresco.library.security.SecurityProfile.STANDARD : node;
        if (own != effective) {
            return "security_profile=" + own.id() + " in wsapi's configuration, but this node's controller runs "
                    + (node == null ? "no known profile" : node.id()) + "; remove the setting from wsapi's configuration (the node's "
                    + "profile applies to every plugin) or set the node's security_profile";
        }
        return null;
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
