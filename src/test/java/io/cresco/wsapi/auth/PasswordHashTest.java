package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHashTest {

    private static final SecureRandom R = new SecureRandom();

    @Test
    void aHashMatchesItsPasswordOnlyAndRoundTrips() {
        PasswordHash h = PasswordHash.create("correct horse battery".toCharArray(), R);
        assertTrue(h.matches("correct horse battery".toCharArray()));
        assertFalse(h.matches("correct horse batterY".toCharArray()));
        assertFalse(h.matches("".toCharArray()));
        PasswordHash back = PasswordHash.parse(h.encode());
        assertTrue(back.matches("correct horse battery".toCharArray()));
        assertEquals(PasswordHash.DEFAULT_ITERATIONS, back.iterations());
        assertTrue(h.encode().startsWith("pbkdf2-sha384$600000$"));
    }

    @Test
    void theSaltIsRandomPerHash() {
        assertNotEquals(PasswordHash.create("same-password-1".toCharArray(), R).encode(),
                PasswordHash.create("same-password-1".toCharArray(), R).encode());
    }

    @Test
    void theAlgorithmIsPbkdf2HmacSha384With384BitOutput() throws Exception {
        String[] p = PasswordHash.create("pw-pw-pw-pw-pw".toCharArray(), R).encode().split("\\$");
        assertEquals(48, Base64.getDecoder().decode(p[3]).length);
        assertEquals(32, Base64.getDecoder().decode(p[2]).length);
        byte[] expect = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA384")
                .generateSecret(new javax.crypto.spec.PBEKeySpec("pw-pw-pw-pw-pw".toCharArray(), Base64.getDecoder().decode(p[2]), 600_000, 384))
                .getEncoded();
        assertArrayEquals(expect, Base64.getDecoder().decode(p[3]));
    }

    @Test
    void weakOrMalformedHashesAreRefused() {
        String salt = Base64.getEncoder().encodeToString(new byte[32]);
        String hash = Base64.getEncoder().encodeToString(new byte[48]);
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha384$599999$" + salt + "$" + hash));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha256$600000$" + salt + "$" + hash));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha384$600000$" + Base64.getEncoder().encodeToString(new byte[8]) + "$" + hash));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha384$600000$" + salt + "$" + Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha384$x$" + salt + "$" + hash));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("plaintext"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse(null));
    }

    @Test
    void toStringNeverShowsTheHash() {
        PasswordHash h = PasswordHash.create("pw-pw-pw-pw-pw".toCharArray(), R);
        assertFalse(h.toString().contains(h.encode().split("\\$")[3]));
    }
}
