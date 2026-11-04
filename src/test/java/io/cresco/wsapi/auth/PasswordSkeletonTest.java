package io.cresco.wsapi.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The password policy sees through how characters look: NFKC, accents, look-alike letters; non-ASCII counts conservatively. */
class PasswordSkeletonTest {

    static String weak(String user, String password) {
        return PasswordPolicy.weakness(user, password.toCharArray());
    }

    @Test
    void wellKnownPasswordsInOtherLettersAreStillWellKnown() {
        String[] weak = {
                "ｐａｓｓｗｏｒｄ１２３４",                     // full-width: NFKC
                "𝐩𝐚𝐬𝐬𝐰𝐨𝐫𝐝1234",                                // mathematical bold: NFKC
                "pässwörd1234",                                  // accents
                "pássword1234",                            // a combining accent
                "Αdmιnιstrαtοr",                                 // Greek look-alikes
                "АDМІNІSТRАТОR",                                 // Cyrillic capitals
                "раsswоrd1234",                                  // Cyrillic а, о, р
                "qwertyuiop١٢٣",                                 // Arabic-Indic digits are digits
                "йцукенгшщзхъ",                                  // qwerty on a Russian keyboard
        };
        for (String w : weak) assertNotNull(weak("alice", w), w);
        assertEquals("it is the user name", weak("administrator", "АDМІNІSТRАТОR"));
    }

    @Test
    void lettersOfAnyScriptCountAsLettersNotAsAHundredSymbols() {
        assertNotNull(weak("bob", "абвгдеабвгде"), "12 lower-case letters: about 56 bits, as in ASCII");
        assertNotNull(weak("bob", "абвгдежзийкл"), "a run in Cyrillic");
        assertNotNull(weak("bob", "αβγδεζαβγδεζ"));
        assertNotNull(weak("bob", "あいうえおかあいうえおか"), "12 kana: about 56 bits, as 12 ASCII letters");
        assertTrue(PasswordPolicy.bits("абвгдеабвгде") < PasswordPolicy.bits("abcdefabcdef") + 0.001);
        assertEquals(PasswordPolicy.bits("éé"), PasswordPolicy.bits("ee"), 0.001, "a combining mark counts nothing");
    }

    @Test
    void strongPasswordsOutsideAsciiAreStillAccepted() {
        assertNull(weak("bob", "кошка окно трава лампа"), "four random Russian words");
        assertNull(weak("bob", "Жёлтый-дом-17-Ветер"), "mixed case, digits, symbols");
        assertNull(weak("bob", "東京タワー夜景の向こう側に"), "13 letters outside ASCII");
        assertNull(weak("alice", "alice-password-1"), "unchanged for ASCII");
        assertNull(weak("alice", "kq7#Vm2!pR9x"), "unchanged for ASCII");
    }
}
