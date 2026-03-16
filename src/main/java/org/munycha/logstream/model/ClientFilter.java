package org.munycha.logstream.model;

import java.util.List;

/**
 * Filter criteria sent by a WebSocket client.
 * Immutable — replaced atomically in the session registry on each "filter" message.
 */
public record ClientFilter(
        String server,
        String path,
        String search,
        boolean regex,
        List<String> keywordTerms,
        String keywordMode,   // "and" | "or"
        String timeRange,     // "all" | "1m" | "5m" | "15m" | "1h" | "custom"
        long timeRangeMs      // used when timeRange == "custom"
) {
    public static final ClientFilter EMPTY = new ClientFilter(null, null, null, false, List.of(), "or", "all", 0);
    private static final List<String> VALID_TIME_RANGES = List.of("all", "1m", "5m", "15m", "1h", "custom");

    public boolean hasServer()   { return server != null && !server.isBlank(); }
    public boolean hasPath()     { return path != null && !path.isBlank(); }
    public boolean hasSearch()   { return search != null && !search.isBlank(); }
    public boolean hasKeywords() { return keywordTerms != null && !keywordTerms.isEmpty(); }
    public boolean hasTimeRange(){
        if (timeRange == null || "all".equals(timeRange)) return false;
        if ("custom".equals(timeRange)) return timeRangeMs > 0;
        return true;
    }

    public boolean isEmpty() {
        return !hasServer() && !hasPath() && !hasSearch() && !hasKeywords() && !hasTimeRange();
    }

    public static ClientFilter sanitize(
            String server,
            String path,
            String search,
            boolean regex,
            List<String> keywordTerms,
            String keywordMode,
            String timeRange,
            long timeRangeMs
    ) {
        List<String> normalizedTerms = keywordTerms == null
                ? List.of()
                : keywordTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(term -> term.trim().toLowerCase())
                .distinct()
                .limit(20)
                .toList();

        String normalizedMode = "and".equalsIgnoreCase(keywordMode) ? "and" : "or";
        String normalizedTimeRange = VALID_TIME_RANGES.contains(timeRange) ? timeRange : "all";
        long normalizedMs = "custom".equals(normalizedTimeRange) && timeRangeMs > 0 ? timeRangeMs : 0;

        return new ClientFilter(
                normalize(server),
                normalize(path),
                normalize(search),
                regex,
                normalizedTerms,
                normalizedMode,
                normalizedTimeRange,
                normalizedMs
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
