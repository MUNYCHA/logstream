package org.munycha.logstream.streaming.websocket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.munycha.logstream.streaming.filter.ClientFilter;

import java.util.List;

/**
 * Raw inbound shape of a client filter — converted to a sanitized {@link ClientFilter}
 * via {@link #toClientFilter()} before being applied.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientFilterRequest(
        String server,
        String path,
        String search,
        Keywords keywords,
        String timeRange,
        long timeRangeMs
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keywords(List<String> terms, String mode) {}

    public ClientFilter toClientFilter() {
        List<String> terms = (keywords != null && keywords.terms() != null) ? keywords.terms() : List.of();
        String mode = (keywords != null && keywords.mode() != null) ? keywords.mode() : "or";
        String range = (timeRange != null) ? timeRange : "all";
        return ClientFilter.sanitize(server, path, search, terms, mode, range, timeRangeMs);
    }
}
