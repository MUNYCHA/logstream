package org.munycha.logstream.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.munycha.logstream.model.ClientFilter;
import org.munycha.logstream.model.LogEvent;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LogFilterEngineTest {

    private LogFilterEngine engine;

    @BeforeEach
    void setUp() {
        engine = new LogFilterEngine();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LogEvent event(String serverName, String path, String message) {
        return new LogEvent(serverName, path, "test-topic", Instant.now().toString(), message);
    }

    private static LogEvent eventWithTimestamp(String timestamp) {
        return new LogEvent("web-01", "/app/app.log", "test-topic", timestamp, "some message");
    }

    private static ClientFilter serverFilter(String server) {
        return ClientFilter.sanitize(server, null, null, List.of(), "or", "all", 0);
    }

    private static ClientFilter pathFilter(String path) {
        return ClientFilter.sanitize(null, path, null, List.of(), "or", "all", 0);
    }

    private static ClientFilter searchFilter(String search) {
        return ClientFilter.sanitize(null, null, search, List.of(), "or", "all", 0);
    }

    private static ClientFilter keywordFilter(List<String> terms, String mode) {
        return ClientFilter.sanitize(null, null, null, terms, mode, "all", 0);
    }

    private static ClientFilter timeRangeFilter(String range) {
        return ClientFilter.sanitize(null, null, null, List.of(), "or", range, 0);
    }

    // -------------------------------------------------------------------------
    // Null / empty filter — always matches
    // -------------------------------------------------------------------------

    @Test
    void matches_nullFilter_alwaysTrue() {
        assertTrue(engine.matches(event("web-01", "/app/app.log", "hello"), null));
    }

    @Test
    void matches_emptyFilter_alwaysTrue() {
        assertTrue(engine.matches(event("web-01", "/app/app.log", "hello"), ClientFilter.EMPTY));
    }

    // -------------------------------------------------------------------------
    // Server filter
    // -------------------------------------------------------------------------

    @Test
    void matches_serverFilter_exactMatch_returnsTrue() {
        assertTrue(engine.matches(event("web-01", "/app/app.log", "msg"), serverFilter("web-01")));
    }

    @Test
    void matches_serverFilter_differentServer_returnsFalse() {
        assertFalse(engine.matches(event("web-02", "/app/app.log", "msg"), serverFilter("web-01")));
    }

    @Test
    void matches_serverFilter_caseSensitive_returnsFalse() {
        assertFalse(engine.matches(event("WEB-01", "/app/app.log", "msg"), serverFilter("web-01")));
    }

    // -------------------------------------------------------------------------
    // Path filter
    // -------------------------------------------------------------------------

    @Test
    void matches_pathFilter_exactMatch_returnsTrue() {
        assertTrue(engine.matches(event("web-01", "/var/log/app.log", "msg"), pathFilter("/var/log/app.log")));
    }

    @Test
    void matches_pathFilter_differentPath_returnsFalse() {
        assertFalse(engine.matches(event("web-01", "/var/log/other.log", "msg"), pathFilter("/var/log/app.log")));
    }

    // -------------------------------------------------------------------------
    // Search (substring)
    // -------------------------------------------------------------------------

    @Test
    void matches_search_messageContainsSubstring_returnsTrue() {
        assertTrue(engine.matches(event("web-01", "/app/app.log", "NullPointerException in Foo"), searchFilter("NullPointerException")));
    }

    @Test
    void matches_search_messageDoesNotContain_returnsFalse() {
        assertFalse(engine.matches(event("web-01", "/app/app.log", "everything is fine"), searchFilter("ERROR")));
    }

    @Test
    void matches_search_caseInsensitive_returnsTrue() {
        assertTrue(engine.matches(event("web-01", "/app/app.log", "NullPointerException in Foo"), searchFilter("nullpointerexception")));
    }

    @Test
    void matches_search_caseInsensitive_upperSearch_returnsTrue() {
        assertTrue(engine.matches(event("web-01", "/app/app.log", "error: connection refused"), searchFilter("ERROR")));
    }

    // -------------------------------------------------------------------------
    // Keywords — AND mode
    // -------------------------------------------------------------------------

    @Test
    void matches_keywordsAnd_allPresent_returnsTrue() {
        LogEvent e = event("web-01", "/app/app.log", "ERROR: timeout connecting to database");
        assertTrue(engine.matches(e, keywordFilter(List.of("error", "timeout", "database"), "and")));
    }

    @Test
    void matches_keywordsAnd_oneAbsent_returnsFalse() {
        LogEvent e = event("web-01", "/app/app.log", "ERROR: timeout");
        assertFalse(engine.matches(e, keywordFilter(List.of("error", "timeout", "database"), "and")));
    }

    @Test
    void matches_keywordsAnd_caseInsensitive_returnsTrue() {
        LogEvent e = event("web-01", "/app/app.log", "ERROR: TIMEOUT");
        assertTrue(engine.matches(e, keywordFilter(List.of("error", "timeout"), "and")));
    }

    // -------------------------------------------------------------------------
    // Keywords — OR mode
    // -------------------------------------------------------------------------

    @Test
    void matches_keywordsOr_onePresent_returnsTrue() {
        LogEvent e = event("web-01", "/app/app.log", "warning: low disk");
        assertTrue(engine.matches(e, keywordFilter(List.of("error", "warning", "critical"), "or")));
    }

    @Test
    void matches_keywordsOr_nonePresent_returnsFalse() {
        LogEvent e = event("web-01", "/app/app.log", "all systems nominal");
        assertFalse(engine.matches(e, keywordFilter(List.of("error", "warning", "critical"), "or")));
    }

    // -------------------------------------------------------------------------
    // Time range
    // -------------------------------------------------------------------------

    @Test
    void matches_timeRange1m_recentEvent_returnsTrue() {
        String ts = Instant.now().minusSeconds(30).toString();
        assertTrue(engine.matches(eventWithTimestamp(ts), timeRangeFilter("1m")));
    }

    @Test
    void matches_timeRange1m_oldEvent_returnsFalse() {
        String ts = Instant.now().minusSeconds(120).toString();
        assertFalse(engine.matches(eventWithTimestamp(ts), timeRangeFilter("1m")));
    }

    @Test
    void matches_timeRange5m_recentEvent_returnsTrue() {
        String ts = Instant.now().minusSeconds(240).toString();
        assertTrue(engine.matches(eventWithTimestamp(ts), timeRangeFilter("5m")));
    }

    @Test
    void matches_timeRange5m_oldEvent_returnsFalse() {
        String ts = Instant.now().minusSeconds(360).toString();
        assertFalse(engine.matches(eventWithTimestamp(ts), timeRangeFilter("5m")));
    }

    @Test
    void matches_timeRange15m_recentEvent_returnsTrue() {
        String ts = Instant.now().minusSeconds(600).toString();
        assertTrue(engine.matches(eventWithTimestamp(ts), timeRangeFilter("15m")));
    }

    @Test
    void matches_timeRange1h_recentEvent_returnsTrue() {
        String ts = Instant.now().minusSeconds(1800).toString();
        assertTrue(engine.matches(eventWithTimestamp(ts), timeRangeFilter("1h")));
    }

    @Test
    void matches_timeRangeAll_oldEvent_returnsTrue() {
        String ts = Instant.now().minusSeconds(99999).toString();
        assertTrue(engine.matches(eventWithTimestamp(ts), timeRangeFilter("all")));
    }

    @Test
    void matches_timeRangeCustom_recentEvent_returnsTrue() {
        String ts = Instant.now().minusSeconds(45).toString();
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of(), "or", "custom", 60_000);
        assertTrue(engine.matches(eventWithTimestamp(ts), f));
    }

    @Test
    void matches_timeRangeCustom_oldEvent_returnsFalse() {
        String ts = Instant.now().minusSeconds(90).toString();
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of(), "or", "custom", 60_000);
        assertFalse(engine.matches(eventWithTimestamp(ts), f));
    }

    @Test
    void matches_timeRangeCustom_zeroMs_treatedAsNoFilter() {
        // custom with timeRangeMs=0 → hasTimeRange() returns false → no filtering
        String ts = Instant.now().minusSeconds(99999).toString();
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of(), "or", "custom", 0);
        assertTrue(engine.matches(eventWithTimestamp(ts), f));
    }

    @Test
    void matches_unparsableTimestamp_letThrough() {
        // Bad timestamp → warn + return true (safe default)
        LogEvent e = new LogEvent("web-01", "/app/app.log", "test-topic", "not-a-timestamp", "msg");
        assertTrue(engine.matches(e, timeRangeFilter("1m")));
    }

    // -------------------------------------------------------------------------
    // Combined filters — short-circuit ordering
    // -------------------------------------------------------------------------

    @Test
    void matches_allFiltersMatch_returnsTrue() {
        String ts = Instant.now().minusSeconds(10).toString();
        LogEvent e = new LogEvent("web-01", "/var/log/app.log", "test-topic", ts, "ERROR timeout");
        ClientFilter f = ClientFilter.sanitize("web-01", "/var/log/app.log", "error", List.of("timeout"), "and", "1m", 0);
        assertTrue(engine.matches(e, f));
    }

    @Test
    void matches_serverMatchesButSearchFails_returnsFalse() {
        LogEvent e = event("web-01", "/app/app.log", "all good here");
        ClientFilter f = ClientFilter.sanitize("web-01", null, "ERROR", List.of(), "or", "all", 0);
        assertFalse(engine.matches(e, f));
    }

    // -------------------------------------------------------------------------
    // ClientFilter.sanitize() — validation and normalization
    // -------------------------------------------------------------------------

    @Test
    void sanitize_keywordsLowercasedAndTrimmed() {
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of("  ERROR  ", "Timeout"), "or", "all", 0);
        assertEquals(List.of("error", "timeout"), f.keywordTerms());
    }

    @Test
    void sanitize_duplicateKeywordsRemoved() {
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of("error", "ERROR", "error"), "or", "all", 0);
        assertEquals(List.of("error"), f.keywordTerms());
    }

    @Test
    void sanitize_blankKeywordsDropped() {
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of("error", "", "  "), "or", "all", 0);
        assertEquals(List.of("error"), f.keywordTerms());
    }

    @Test
    void sanitize_keywordsLimitedTo20() {
        List<String> manyTerms = IntStream.rangeClosed(1, 30).mapToObj(i -> "term" + i).toList();
        ClientFilter f = ClientFilter.sanitize(null, null, null, manyTerms, "or", "all", 0);
        assertEquals(20, f.keywordTerms().size());
    }

    @Test
    void sanitize_invalidKeywordMode_defaultsToOr() {
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of("x"), "INVALID", "all", 0);
        assertEquals("or", f.keywordMode());
    }

    @Test
    void sanitize_invalidTimeRange_defaultsToAll() {
        ClientFilter f = ClientFilter.sanitize(null, null, null, List.of(), "or", "bad-range", 0);
        assertEquals("all", f.timeRange());
    }

    @Test
    void sanitize_blankServer_treatedAsNoFilter() {
        ClientFilter f = ClientFilter.sanitize("   ", null, null, List.of(), "or", "all", 0);
        assertFalse(f.hasServer());
    }

    @Test
    void sanitize_blankSearch_treatedAsNoFilter() {
        ClientFilter f = ClientFilter.sanitize(null, null, "   ", List.of(), "or", "all", 0);
        assertFalse(f.hasSearch());
    }
}
