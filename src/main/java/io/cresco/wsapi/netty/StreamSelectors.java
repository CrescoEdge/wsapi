package io.cresco.wsapi.netty;

import io.cresco.wsapi.websockets.StreamInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the JMS selector a dataplane client subscribes with, and refuses anything that could
 * change what that selector matches. The handshake used to concatenate the client's ident_key and
 * ident_id into {@code key='id'}, or take a raw stream_query verbatim, so a client could close the
 * quote and subscribe to every stream on its shard (GaiaKeep OUT-28).
 *
 * <p>Allowed: an ident_key that is a plain identifier (not a JMS reserved word, not a JMS header),
 * an ident_id from a strict character set without quotes, and a raw stream_query of the form
 * {@code term (AND|OR term)*}, where a term is {@code ident = 'value'}, {@code ident <> 'value'} or
 * {@code ident IS [NOT] NULL}. No parentheses, LIKE, IN, BETWEEN, arithmetic or escaped quotes.
 */
public final class StreamSelectors {

    public static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    /** Characters allowed in an ident_id and in a quoted value: no quote, no control characters. */
    public static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.:@/+\\-]{1,256}");
    public static final int MAX_QUERY = 1024;
    private static final Set<String> RESERVED = Set.of("NOT", "AND", "OR", "BETWEEN", "LIKE", "IN", "IS", "NULL",
            "TRUE", "FALSE", "ESCAPE");

    private StreamSelectors() {}

    /** A property name the client may use: an identifier, not a keyword, not a JMS header. */
    public static boolean identifier(String s) {
        return s != null && IDENT.matcher(s).matches() && !RESERVED.contains(s.toUpperCase(Locale.ROOT))
                && !s.toUpperCase(Locale.ROOT).startsWith("JMS");
    }

    public static boolean value(String s) { return s != null && VALUE.matcher(s).matches(); }

    /** The selector for a handshake, or an IllegalArgumentException saying what was refused. */
    public static String selectorFor(StreamInfo si) {
        if (si.getStream_query() != null) {
            String q = si.getStream_query();
            requireSafeQuery(q);
            return q;
        }
        if (!identifier(si.getIdentKey())) throw new IllegalArgumentException("ident_key must be a plain identifier");
        if (!value(si.getIdentId())) throw new IllegalArgumentException("ident_id must match " + VALUE.pattern());
        if (si.getIoTypeKey() != null && !identifier(si.getIoTypeKey())) throw new IllegalArgumentException("io_type_key must be a plain identifier");
        for (String v : new String[]{si.getInputId(), si.getOutputId()})
            if (v != null && !value(v)) throw new IllegalArgumentException("input_id and output_id must match " + VALUE.pattern());
        return si.getIdentKey() + "='" + si.getIdentId() + "'";
    }

    /** Refuse a raw stream_query outside the safe grammar. */
    public static void requireSafeQuery(String q) {
        if (q == null || q.isBlank()) throw new IllegalArgumentException("empty stream_query");
        if (q.length() > MAX_QUERY) throw new IllegalArgumentException("stream_query longer than " + MAX_QUERY);
        List<String> t = tokens(q);
        int i = 0;
        for (;;) {
            // term
            if (i >= t.size() || !identifier(t.get(i))) throw bad("an identifier", t, i);
            i++;
            if (i < t.size() && (t.get(i).equals("=") || t.get(i).equals("<>"))) {
                i++;
                if (i >= t.size() || !t.get(i).startsWith("'")) throw bad("a quoted value", t, i);
                i++;
            } else if (i < t.size() && t.get(i).equalsIgnoreCase("IS")) {
                i++;
                if (i < t.size() && t.get(i).equalsIgnoreCase("NOT")) i++;
                if (i >= t.size() || !t.get(i).equalsIgnoreCase("NULL")) throw bad("NULL", t, i);
                i++;
            } else {
                throw bad("=, <> or IS", t, i);
            }
            if (i == t.size()) return;
            if (!t.get(i).equalsIgnoreCase("AND") && !t.get(i).equalsIgnoreCase("OR")) throw bad("AND or OR", t, i);
            i++;
        }
    }

    private static IllegalArgumentException bad(String expected, List<String> t, int i) {
        return new IllegalArgumentException("stream_query refused: expected " + expected + " at token " + (i + 1)
                + (i < t.size() ? "" : " (end of query)"));
    }

    /** Words, the two comparison operators, and quoted values (whose content must match VALUE). */
    static List<String> tokens(String q) {
        List<String> out = new ArrayList<>();
        int i = 0, n = q.length();
        while (i < n) {
            char c = q.charAt(i);
            if (c == ' ' || c == '\t') { i++; continue; }
            if (c == '=') { out.add("="); i++; continue; }
            if (c == '<' && i + 1 < n && q.charAt(i + 1) == '>') { out.add("<>"); i += 2; continue; }
            if (c == '\'') {
                int end = q.indexOf('\'', i + 1);
                if (end < 0) throw new IllegalArgumentException("stream_query refused: unterminated quote");
                String v = q.substring(i + 1, end);
                if (!v.isEmpty() && !value(v)) throw new IllegalArgumentException("stream_query refused: a quoted value must match " + VALUE.pattern());
                out.add("'" + v + "'");
                i = end + 1;
                if (i < n && q.charAt(i) == '\'') throw new IllegalArgumentException("stream_query refused: escaped quotes are not allowed");
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(q.charAt(i)) || q.charAt(i) == '_')) i++;
                out.add(q.substring(s, i));
                continue;
            }
            throw new IllegalArgumentException("stream_query refused: character '" + (c < 0x20 || c > 0x7e ? "?" : String.valueOf(c)) + "' is not allowed");
        }
        return out;
    }
}
