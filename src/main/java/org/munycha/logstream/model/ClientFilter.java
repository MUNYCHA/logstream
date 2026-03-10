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
        String timeRange      // "all" | "1m" | "5m" | "15m" | "1h"
) {
    public static final ClientFilter EMPTY = new ClientFilter(null, null, null, false, List.of(), "or", "all");

    public boolean hasServer()   { return server != null && !server.isBlank(); }
    public boolean hasPath()     { return path != null && !path.isBlank(); }
    public boolean hasSearch()   { return search != null && !search.isBlank(); }
    public boolean hasKeywords() { return keywordTerms != null && !keywordTerms.isEmpty(); }
    public boolean hasTimeRange(){ return timeRange != null && !"all".equals(timeRange); }

    public boolean isEmpty() {
        return !hasServer() && !hasPath() && !hasSearch() && !hasKeywords() && !hasTimeRange();
    }
}
