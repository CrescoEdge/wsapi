package io.cresco.wsapi.auth;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a wsapi password must be: at least {@link #MIN_CHARS} characters, {@link #MIN_DISTINCT} different ones, about
 * {@link #MIN_BITS} bits estimated from its length and character classes, not a well-known password (or one with only
 * digits and symbols around a well-known word), not the user name, and not a run like {@code abcdefghijklm}.
 * {@link CredentialTool} enforces it when a password is set, and the {@link Authenticator} when one is used, so a weak
 * password set before this rule (or written into the file by hand) stops working until it is changed.
 *
 * <p>Why it matters: strangers who know a user name can make wsapi check their guesses at a bounded rate
 * ({@link AuthRateLimiter}), never zero, so the password itself has to be out of reach. At 60 bits, even a million
 * checks a day take more than a billion years on average. Automation should use API tokens (256 bits, never throttled
 * per user) instead of passwords.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARS = 12;
    public static final int MIN_DISTINCT = 6;
    public static final int MIN_BITS = 60;

    /** Well-known passwords (and the words they are built on), compared without case. */
    static final Set<String> WEAK = Set.of(
            "password", "passw0rd", "p@ssword", "p@ssw0rd", "qwerty", "qwertyuiop", "qwertyuiopasdfghjkl", "asdfghjkl",
            "zxcvbnm", "letmein", "welcome", "admin", "administrator", "changeit", "changeme", "cresco", "iloveyou",
            "monkey", "dragon", "football", "baseball", "master", "sunshine", "princess", "trustno1", "superman",
            "starwars", "whatever", "secret", "default", "root", "toor", "guest", "test", "hunter", "login", "access",
            "shadow", "michael", "jennifer", "computer", "internet", "correcthorsebatterystaple", "opensesame");

    private PasswordPolicy() {}

    /** Why {@code password} is too weak for {@code user}, or null when it is acceptable. Never echoes the password. */
    public static String weakness(String user, char[] password) {
        if (password == null || password.length < MIN_CHARS) return "it has fewer than " + MIN_CHARS + " characters";
        String p = new String(password);
        String lower = p.toLowerCase(Locale.ROOT);
        Set<Integer> distinct = new HashSet<>();
        p.codePoints().forEach(distinct::add);
        if (distinct.size() < MIN_DISTINCT) return "it uses fewer than " + MIN_DISTINCT + " different characters";
        if (user != null && lower.equals(user.toLowerCase(Locale.ROOT))) return "it is the user name";
        if (WEAK.contains(lower)) return "it is a well-known password";
        String letters = lower.replaceAll("[^a-z]", "");
        if (letters.length() >= 4 && (WEAK.contains(letters) || (user != null && letters.equals(user.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""))))
                && p.length() - letters.length() < 6) {
            return "it is a well-known word or the user name with a few digits or symbols around it";
        }
        if (run(p)) return "it is a run of consecutive characters";
        double bits = bits(p);
        if (bits < MIN_BITS) {
            return "it has about " + (int) bits + " bits; at least " + MIN_BITS + " are needed (e.g. 13 random letters, 12 mixed "
                    + "characters, or four random words)";
        }
        return null;
    }

    /** Estimated bits: length times log2 of the size of the character classes used. */
    static double bits(String p) {
        boolean lower = false, upper = false, digit = false, symbol = false, other = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= '0' && c <= '9') digit = true;
            else if (c >= 0x20 && c < 0x7f) symbol = true;
            else other = true;
        }
        int pool = (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0) + (other ? 100 : 0);
        return p.codePointCount(0, p.length()) * (Math.log(Math.max(2, pool)) / Math.log(2));
    }

    /** Every character one more, or one less, than the one before (abcdef..., 987654...). */
    private static boolean run(String p) {
        int up = 0, down = 0;
        for (int i = 1; i < p.length(); i++) {
            int d = p.charAt(i) - p.charAt(i - 1);
            if (d == 1) up++;
            if (d == -1) down++;
        }
        return up == p.length() - 1 || down == p.length() - 1;
    }
}
