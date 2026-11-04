package io.cresco.wsapi.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA-384 password hashes for wsapi credentials, in the form
 * {@code pbkdf2-sha384$<iterations>$<salt base64>$<hash base64>}.
 *
 * <p>At least {@link #MIN_ITERATIONS} iterations (OWASP 2023 guidance for PBKDF2-HMAC-SHA-256 is 600,000; SHA-384 is
 * slower per iteration, so this is stronger), a random salt of {@link #SALT_BYTES} bytes per hash, a 384-bit derived
 * key, and a constant-time comparison. Pure JDK so the credential tool runs from the wsapi jar alone.
 */
public final class PasswordHash {

    /** The algorithm tag stored with each hash. */
    public static final String SCHEME = "pbkdf2-sha384";
    /** The JCA name of the KDF (matches {@code CrescoCrypto.KDF_ALG}). */
    public static final String KDF = "PBKDF2WithHmacSHA384";
    /** The floor: a stored hash with fewer iterations is refused when the store is loaded. */
    public static final int MIN_ITERATIONS = 600_000;
    /** Iterations for new hashes. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    /** Salt length for new hashes. */
    public static final int SALT_BYTES = 32;
    /** Shortest salt a stored hash may carry. */
    public static final int MIN_SALT_BYTES = 16;
    /** Derived key length (bits). */
    public static final int KEY_BITS = 384;
    /** Shortest password the credential tool accepts. */
    public static final int MIN_PASSWORD_CHARS = 12;

    private final int iterations;
    private final byte[] salt;
    private final byte[] hash;

    private PasswordHash(int iterations, byte[] salt, byte[] hash) {
        this.iterations = iterations;
        this.salt = salt;
        this.hash = hash;
    }

    /** A new hash of {@code secret} with a fresh random salt. */
    public static PasswordHash create(char[] secret, SecureRandom random) {
        return create(secret, random, DEFAULT_ITERATIONS);
    }

    static PasswordHash create(char[] secret, SecureRandom random, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new PasswordHash(iterations, salt, derive(secret, salt, iterations));
    }

    /**
     * Parse a stored hash.
     *
     * @throws IllegalArgumentException a malformed hash, another scheme, fewer than {@link #MIN_ITERATIONS}
     *         iterations, a short salt or a wrong-length hash (the message never repeats the hash)
     */
    public static PasswordHash parse(String stored) {
        if (stored == null) throw new IllegalArgumentException("no password hash");
        String[] p = stored.split("\\$", -1);
        if (p.length != 4 || !SCHEME.equals(p[0])) {
            throw new IllegalArgumentException("not a " + SCHEME + "$<iterations>$<salt>$<hash> password hash");
        }
        int it;
        try {
            it = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("password hash iterations is not a number");
        }
        if (it < MIN_ITERATIONS) {
            throw new IllegalArgumentException("password hash has " + it + " iterations; at least " + MIN_ITERATIONS + " are required");
        }
        byte[] salt, hash;
        try {
            salt = Base64.getDecoder().decode(p[2]);
            hash = Base64.getDecoder().decode(p[3]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("password hash salt or value is not base64");
        }
        if (salt.length < MIN_SALT_BYTES) throw new IllegalArgumentException("password hash salt is shorter than " + MIN_SALT_BYTES + " bytes");
        if (hash.length != KEY_BITS / 8) throw new IllegalArgumentException("password hash is not " + (KEY_BITS / 8) + " bytes");
        return new PasswordHash(it, salt, hash);
    }

    /** True if {@code secret} is the password this hash was made from. Constant time in the comparison. */
    public boolean matches(char[] secret) {
        byte[] candidate = derive(secret, salt, iterations);
        try {
            return MessageDigest.isEqual(candidate, hash);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    public int iterations() { return iterations; }

    /** The stored form. */
    public String encode() {
        return SCHEME + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }

    @Override
    public String toString() { return SCHEME + "$" + iterations + "$<salt>$<hash>"; }

    static byte[] derive(char[] secret, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(KDF + " unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
