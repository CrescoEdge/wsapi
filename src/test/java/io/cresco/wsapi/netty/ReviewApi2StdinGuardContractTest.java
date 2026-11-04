package io.cresco.wsapi.netty;

import io.cresco.wsapi.websockets.StreamInfo;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 2 (review/api-20261104): wsapi and the controller (hardening 7d7cfd5) now both guard stdin,
 * and the two guards are incompatible. wsapi appends {@link StreamSelectors#NO_STDIN} to every subscription; the
 * controller's per-bundle dataplane service refuses, for every bundle but the executor, any selector that names stdin,
 * by this pattern (controller StdinGuard.java:28, applied in BundleDataPlaneService.java:55), and NO_STDIN names it.
 * Live, against the hardening controller: every wsapi dataplane subscription is refused ("a dataplane selector naming a
 * process's stdin ... is refused"), so no API client (GaiaKeep tooling included) can receive on the dataplane.
 */
class ReviewApi2StdinGuardContractTest {

    /** The controller's StdinGuard.NAMES_STDIN, copied: a selector it matches is refused. */
    static final Pattern CONTROLLER_NAMES_STDIN = Pattern.compile("'input(-|'|%|_)", Pattern.CASE_INSENSITIVE);

    @Test
    void anOrdinarySubscriptionPassesTheControllersStdinGuard() {
        StreamInfo si = new StreamInfo("t", "stream_name", "gk-data-1");
        si.setIoTypeKey("type");
        si.setInputId("data");
        String selector = StreamSelectors.subscriptionSelector(si);
        assertFalse(CONTROLLER_NAMES_STDIN.matcher(selector).find(),
                "the controller refuses wsapi's own subscription selector: " + selector);
    }
}
