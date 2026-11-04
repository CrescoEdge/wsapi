package io.cresco.wsapi.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Test helpers for the auth package. */
final class PasswordHashTestSupport {

    private PasswordHashTestSupport() {}

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
