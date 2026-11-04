package io.cresco.wsapi.auth;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a wsapi password must be: at least {@link #MIN_CHARS} characters, {@link #MIN_DISTINCT} different ones, about
 * {@link #MIN_BITS} bits estimated from its length and character classes, not a well-known password (or one with only
 * digits and symbols around a well-known word), not the user name, and not a run like {@code abcdefghijklm}.
 * {@link CredentialTool} enforces it when a password is set, and the {@link Authenticator} when one is used, so a weak
 * password set before this rule (or written into the file by hand) stops working until it is changed.
 *
 * <p>Every check sees through the look of the characters: the password is NFKC-normalised (full-width and mathematical
 * letters become ASCII), accents are dropped, and letters that look like ASCII letters (Cyrillic {@code а с е о р х},
 * Greek {@code ο ρ α}, ...) become them, giving an ASCII <em>skeleton</em> that is compared with the well-known list,
 * the user name and runs. The strength estimate is conservative outside ASCII: a letter of any script counts as one of
 * 26 letters of its case (an ASCII one, when it looks like one), any other character as one of the 33 ASCII symbols, and
 * combining marks count nothing. ({@code абвгдеабвгде} is the weak {@code abcdefabcdef} in other letters; it used to
 * count each character as one of 100 symbols.)
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

    /** Well-known passwords (and the words they are built on), compared without case, as ASCII skeletons. */
    static final Set<String> WEAK = Set.of(
            "password", "passw0rd", "p@ssword", "p@ssw0rd", "qwerty", "qwertyuiop", "qwertyuiopasdfghjkl", "asdfghjkl",
            "zxcvbnm", "letmein", "welcome", "admin", "administrator", "changeit", "changeme", "cresco", "iloveyou",
            "monkey", "dragon", "football", "baseball", "master", "sunshine", "princess", "trustno1", "superman",
            "starwars", "whatever", "secret", "default", "root", "toor", "guest", "test", "hunter", "login", "access",
            "shadow", "michael", "jennifer", "computer", "internet", "correcthorsebatterystaple", "opensesame",
            // the common ones typed on a Russian keyboard (the Latin qwerty keys) and the Russian word for password
            "йцукен", "йцукенгшщз", "йцукенгшщзхъ", "фывапролджэ", "ячсмить", "пароль");

    /**
     * Letters that look like ASCII letters (after NFKC and with accents dropped): Cyrillic, Greek, Armenian, Cherokee and
     * Latin look-alikes, after the Unicode confusables (UTS #39) for the letters people substitute.
     */
    static final Map<Integer, Character> CONFUSABLE = new HashMap<>();

    static {
        String[] pairs = {
                // Cyrillic lower case
                "а", "a", "в", "b", "г", "r", "е", "e", "ё", "e", "к", "k", "м", "m", "н", "h", "о", "o", "п", "n",
                "р", "p", "с", "c", "т", "t", "у", "y", "х", "x", "ь", "b", "і", "i", "ї", "i", "ј", "j", "ѕ", "s", "ԁ", "d",
                "ԛ", "q", "ԝ", "w", "ӏ", "l", "һ", "h", "ɡ", "g", "ү", "y", "ѵ", "v", "ԍ", "g", "ꙇ", "i",
                // Cyrillic upper case
                "А", "A", "В", "B", "Е", "E", "Ё", "E", "К", "K", "М", "M", "Н", "H", "О", "O", "Р", "P", "С", "C",
                "Т", "T", "У", "Y", "Х", "X", "Ь", "b", "І", "I", "Ї", "I", "Ј", "J", "Ѕ", "S", "Ԁ", "D", "Ԛ", "Q", "Ԝ", "W",
                "Ӏ", "l", "Һ", "H", "Ү", "Y", "Ѵ", "V", "Ԍ", "G",
                // Greek
                "α", "a", "β", "b", "γ", "y", "ε", "e", "η", "n", "ι", "i", "κ", "k", "ν", "v", "ο", "o", "ρ", "p", "σ", "o",
                "τ", "t", "υ", "u", "χ", "x", "ω", "w", "ϲ", "c", "ϳ", "j",
                "Α", "A", "Β", "B", "Ε", "E", "Ζ", "Z", "Η", "H", "Ι", "I", "Κ", "K", "Μ", "M", "Ν", "N", "Ο", "O", "Ρ", "P",
                "Τ", "T", "Υ", "Y", "Χ", "X", "Ϲ", "C", "Ϳ", "J",
                // Armenian
                "օ", "o", "ս", "u", "ո", "n", "հ", "h", "ց", "g", "զ", "q", "ա", "w", "Օ", "O", "Ս", "U", "Տ", "S",
                // Cherokee capitals
                "Ꭺ", "A", "Ᏼ", "B", "Ꮯ", "C", "Ꭼ", "E", "Ꮐ", "G", "Ꮋ", "H", "Ꭻ", "J", "Ꮶ", "K", "Ꮮ", "L", "Ꮇ", "M", "Ꮲ", "P",
                "Ꮪ", "S", "Ꭲ", "T", "Ꮩ", "V", "Ꮃ", "W", "Ꮓ", "Z",
                // Latin look-alikes that NFKC and accent stripping keep
                "ı", "i", "ȷ", "j", "ł", "l", "ø", "o", "đ", "d", "ħ", "h", "ŧ", "t", "ƅ", "b", "ɑ", "a", "ɩ", "i", "ʟ", "l",
                "ɪ", "i", "ʀ", "r", "ᴀ", "a", "ᴄ", "c", "ᴅ", "d", "ᴇ", "e", "ᴊ", "j", "ᴋ", "k", "ᴍ", "m", "ᴏ", "o", "ᴘ", "p",
                "ᴛ", "t", "ᴜ", "u", "ᴠ", "v", "ᴡ", "w", "ᴢ", "z", "ꜱ", "s", "Ø", "O", "Ł", "L", "Đ", "D", "Ħ", "H",
        };
        for (int i = 0; i < pairs.length; i += 2) CONFUSABLE.put(pairs[i].codePointAt(0), pairs[i + 1].charAt(0));
    }

    private PasswordPolicy() {}

    /** Why {@code password} is too weak for {@code user}, or null when it is acceptable. Never echoes the password. */
    public static String weakness(String user, char[] password) {
        if (password == null) return "it has fewer than " + MIN_CHARS + " characters";
        String p = Normalizer.normalize(new String(password), Normalizer.Form.NFKC);
        if (length(p) < MIN_CHARS) return "it has fewer than " + MIN_CHARS + " characters";
        String skeleton = skeleton(p);
        String lower = skeleton.toLowerCase(Locale.ROOT);
        Set<Integer> distinct = new HashSet<>();
        skeleton.codePoints().forEach(distinct::add);
        if (distinct.size() < MIN_DISTINCT) return "it uses fewer than " + MIN_DISTINCT + " different characters";
        String name = user == null ? null : skeleton(Normalizer.normalize(user, Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT);
        if (name != null && lower.equals(name)) return "it is the user name";
        if (WEAK.contains(lower)) return "it is a well-known password";
        String letters = letters(lower);
        if (letters.codePointCount(0, letters.length()) >= 4
                && (WEAK.contains(letters) || (name != null && letters.equals(letters(name))))
                && length(skeleton) - letters.codePointCount(0, letters.length()) < 6) {
            return "it is a well-known word or the user name with a few digits or symbols around it";
        }
        if (run(p) || run(skeleton)) return "it is a run of consecutive characters";
        double bits = bits(p);
        if (bits < MIN_BITS) {
            return "it has about " + (int) bits + " bits; at least " + MIN_BITS + " are needed (e.g. 13 random letters, 12 mixed "
                    + "characters, or four random words)";
        }
        return null;
    }

    /**
     * The ASCII skeleton of an NFKC-normalised text: accents and other combining marks dropped, look-alike letters
     * ({@link #CONFUSABLE}) and decimal digits of any script replaced by their ASCII forms; other characters kept.
     */
    static String skeleton(String nfkc) {
        String d = Normalizer.normalize(nfkc, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        d.codePoints().forEach(cp -> {
            if (isMark(cp)) return;
            Character ascii = CONFUSABLE.get(cp);
            if (ascii != null) {
                sb.append(ascii.charValue());
            } else if (cp > 0x7f && Character.isDigit(cp) && Character.digit(cp, 10) >= 0) {
                sb.append((char) ('0' + Character.digit(cp, 10)));
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return Normalizer.normalize(sb, Normalizer.Form.NFC);
    }

    private static boolean isMark(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK;
    }

    /** Its letters, of any script. */
    private static String letters(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().filter(Character::isLetter).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** Characters, combining marks not counted. */
    private static int length(String s) {
        return (int) s.codePoints().filter(cp -> !isMark(cp)).count();
    }

    /**
     * Estimated bits: length times log2 of the size of the character classes used. Conservative outside ASCII: a letter
     * of any script is one of 26 of its case (lower case and caseless letters count as lower case), any other non-ASCII
     * character one of the 33 symbols; combining marks count nothing.
     */
    static double bits(String p) {
        boolean lower = false, upper = false, digit = false, symbol = false;
        int n = 0;
        for (int cp : Normalizer.normalize(p, Normalizer.Form.NFKC).codePoints().toArray()) {
            if (isMark(cp)) continue;
            n++;
            Character ascii = CONFUSABLE.get(cp);
            int c = ascii != null ? ascii : cp;
            if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= '0' && c <= '9') digit = true;
            else if (c >= 0x20 && c < 0x7f) symbol = true;
            else if (Character.isLetter(c)) {
                if (Character.isUpperCase(c) || Character.isTitleCase(c)) upper = true;
                else lower = true;
            } else if (Character.isDigit(c)) digit = true;
            else symbol = true;
        }
        int pool = (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0);
        return n * (Math.log(Math.max(2, pool)) / Math.log(2));
    }

    /** Every character one more, or one less, than the one before (abcdef..., 987654..., абвгде...). */
    private static boolean run(String p) {
        int[] c = p.codePoints().filter(cp -> !isMark(cp)).toArray();
        if (c.length < 2) return false;
        int up = 0, down = 0;
        for (int i = 1; i < c.length; i++) {
            int d = c[i] - c[i - 1];
            if (d == 1) up++;
            if (d == -1) down++;
        }
        return up == c.length - 1 || down == c.length - 1;
    }
}
