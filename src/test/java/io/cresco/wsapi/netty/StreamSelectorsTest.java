package io.cresco.wsapi.netty;

import io.cresco.wsapi.websockets.StreamInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** A dataplane client cannot shape the JMS selector it subscribes with. */
class StreamSelectorsTest {

    static StreamInfo ident(String key, String id) { return new StreamInfo("t", key, id); }

    @Test
    void theIdentHandshakeBuildsItsSelector() {
        assertEquals("stream_name='gfs-7f3a.up_01'", StreamSelectors.selectorFor(ident("stream_name", "gfs-7f3a.up_01")));
        StreamInfo si = ident("stream_name", "abc");
        si.setIoTypeKey("type");
        si.setInputId("input");
        si.setOutputId("output");
        assertEquals("stream_name='abc'", StreamSelectors.selectorFor(si));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x' OR stream_name IS NOT NULL OR 'a'='a", "x'", "a b", "", "x\u0000", "x\n", "é",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void anIdentIdThatCouldEscapeItsQuotesIsRefused(String id) {
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(ident("stream_name", id)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stream_name='x' OR 1=1 OR a", "a b", "1abc", "OR", "NULL", "JMSType", "JMSXGroupID", "a=b", "x'", ""})
    void anIdentKeyThatIsNotAPlainIdentifierIsRefused(String key) {
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(ident(key, "abc")));
    }

    @Test
    void ioTypeKeyAndIdsAreCheckedToo() {
        StreamInfo si = ident("stream_name", "abc");
        si.setIoTypeKey("type' OR 'a'='a");
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(si));
        StreamInfo si2 = ident("stream_name", "abc");
        si2.setInputId("in'put");
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(si2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cresco_msg_type='stunnel_trace'", "cresco_msg_type='gfs_state'", "stream_name='a' and type='outgoing'",
            "region_id IS NOT NULL", "a <> 'b' OR c IS NULL", "stream_name = 'x-1.y:z@h/p+q'", "t=''"})
    void rawQueriesInTheSafeGrammarAreAccepted(String q) {
        assertEquals(q, StreamSelectors.selectorFor(new StreamInfo("t", q)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cresco_msg_type LIKE '%'", "a='x' OR (1=1)", "a IN ('x','y')", "a BETWEEN 1 AND 2", "1=1",
            "a='it''s'", "a='x", "a=b", "a='x' b='y'", "a='x' AND", "NOT a='x'", "a='x';DROP", "a='x'\nOR b IS NOT NULL",
            "JMSMessageID IS NOT NULL", "a = 'x y'", "a > 'x'", "", "   "})
    void rawQueriesOutsideTheGrammarAreRefused(String q) {
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(new StreamInfo("t", q)));
    }

    @Test
    void anOversizedQueryIsRefused() {
        StringBuilder q = new StringBuilder("a='x'");
        while (q.length() <= StreamSelectors.MAX_QUERY) q.append(" OR a='x'");
        assertThrows(IllegalArgumentException.class, () -> StreamSelectors.selectorFor(new StreamInfo("t", q.toString())));
    }

    @Test
    void theServiceKeyIsComparedWithoutEarlyExit() {
        assertTrue(WsRouter.keyMatches("s3rvice-key", "s3rvice-key"));
        assertFalse(WsRouter.keyMatches("s3rvice-key", "s3rvice-kez"));
        assertFalse(WsRouter.keyMatches("s3rvice-key", "s3rvice-ke"));
        assertFalse(WsRouter.keyMatches("s3rvice-key", ""));
        assertFalse(WsRouter.keyMatches("s3rvice-key", null));
        assertFalse(WsRouter.keyMatches(null, "x"));
        assertTrue(WsRouter.keyMatches("ключ-κλειδί", "ключ-κλειδί"), "compared as UTF-8 bytes");
    }
}
