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
 * {@code term (AND term)*}, where a term is {@code ident = 'value'}, {@code ident <> 'value'} or a
 * bare boolean property {@code ident}, and at least one term is {@code ident = 'value'} with a
 * non-empty value. A query can therefore only narrow one named stream or message type: OR,
 * {@code IS [NOT] NULL} and a query of {@code <>} terms alone are refused, because each can match
 * every message on the shard ({@code nonexistent IS NULL} selects everything). No parentheses, NOT,
 * LIKE, IN, BETWEEN, arithmetic or escaped quotes.
 *
 * <p>A client never receives a process's stdin: every subscription wsapi makes is the client's selector
 * {@code AND} {@link #NO_STDIN} ({@link #subscriptionSelector}). The executor takes stdin only from messages whose
 * {@code type} is the runner's unguessable {@code input-<hex>} (or the development-only {@code input}); a subscriber of
 * the stream would otherwise read every line others type into it, and learn the type that lets it type too.
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

    /** Property names only infrastructure writes (filerepo frames, executor and logger events, wsapi's own stamps). */
    static final Set<String> INFRASTRUCTURE_NAMES = Set.of("seq_num", "offset", "total_bytes", "sha384", "start_byte",
            "byte_length", "dp_bytes", "region_id", "agent_id", "plugin_id", "pluginname", "event", "loglevel", "logid",
            "session_id");

    /**
     * True for a property name a dataplane client may not write: {@code cresco_*} (the verified caller and writer, tenant
     * routing), {@code transfer_*} and the other names infrastructure stamps ({@link #INFRASTRUCTURE_NAMES}), any case.
     */
    public static boolean reservedName(String s) {
        if (s == null) return true;
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("cresco_") || l.startsWith("transfer_") || INFRASTRUCTURE_NAMES.contains(l);
    }

    /** The values a query compares {@code ident} to with {@code =} (the AND terms of a safe query). */
    public static List<String> equalityValues(String q, String ident) {
        List<String> out = new ArrayList<>();
        List<String> t = tokens(q);
        for (int i = 0; i + 2 < t.size(); i++) {
            if (t.get(i).equals(ident) && t.get(i + 1).equals("=") && t.get(i + 2).startsWith("'")) {
                String v = t.get(i + 2);
                out.add(v.substring(1, v.length() - 1));
            }
        }
        return out;
    }

    public static boolean value(String s) { return s != null && VALUE.matcher(s).matches(); }

    /** Matches no stdin message: no {@code type}, or a {@code type} other than {@code input} and {@code input-*}. */
    public static final String NO_STDIN = "(type IS NULL OR (type <> 'input' AND type NOT LIKE 'input-%'))";

    /** The selector wsapi subscribes with for a handshake: the client's, narrowed by {@link #NO_STDIN}. */
    public static String subscriptionSelector(StreamInfo si) {
        return "(" + selectorFor(si) + ") AND " + NO_STDIN;
    }

    /** The selector for a handshake, or an IllegalArgumentException saying what was refused. */
    public static String selectorFor(StreamInfo si) {
        if (si.getStream_query() != null) {
            String q = si.getStream_query();
            requireSafeQuery(q);
            return q;
        }
        if (!identifier(si.getIdentKey())) throw new IllegalArgumentException("ident_key must be a plain identifier");
        if (reservedName(si.getIdentKey())) throw new IllegalArgumentException("ident_key " + si.getIdentKey() + " is a property only infrastructure writes");
        if (!value(si.getIdentId())) throw new IllegalArgumentException("ident_id must match " + VALUE.pattern());
        if (si.getIoTypeKey() != null && !identifier(si.getIoTypeKey())) throw new IllegalArgumentException("io_type_key must be a plain identifier");
        if (si.getIoTypeKey() != null && reservedName(si.getIoTypeKey())) throw new IllegalArgumentException("io_type_key " + si.getIoTypeKey() + " is a property only infrastructure writes");
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
        boolean anchored = false;
        for (;;) {
            // term: ident = 'v' | ident <> 'v' | ident (a boolean property)
            if (i >= t.size() || !identifier(t.get(i))) throw bad("an identifier", t, i);
            i++;
            if (i < t.size() && (t.get(i).equals("=") || t.get(i).equals("<>"))) {
                boolean eq = t.get(i).equals("=");
                i++;
                if (i >= t.size() || !t.get(i).startsWith("'")) throw bad("a quoted value", t, i);
                if (eq && t.get(i).length() > 2) anchored = true;
                i++;
            }
            if (i == t.size()) break;
            if (!t.get(i).equalsIgnoreCase("AND")) throw bad("AND", t, i);
            i++;
        }
        // Without one positive equality the query can match every message on the shard.
        if (!anchored) throw new IllegalArgumentException("stream_query refused: it needs at least one ident = 'value' term, "
                + "so it can only narrow a named stream or message type");
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
