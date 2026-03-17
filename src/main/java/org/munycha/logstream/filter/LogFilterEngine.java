package org.munycha.logstream.filter;

import org.munycha.logstream.model.ClientFilter;
import org.munycha.logstream.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Stateless engine that evaluates whether a LogEvent passes a ClientFilter.
 * Called on the broadcast hot-path — keep it fast.
 */
@Component
public class LogFilterEngine {

    private static final Logger log = LoggerFactory.getLogger(LogFilterEngine.class);

    private static final Map<String, Duration> TIME_RANGES = Map.of(
            "1m",  Duration.ofMinutes(1),
            "5m",  Duration.ofMinutes(5),
            "15m", Duration.ofMinutes(15),
            "1h",  Duration.ofHours(1)
    );

    /**
     * Returns true if the event matches all criteria in the filter.
     */
    public boolean matches(LogEvent event, ClientFilter filter) {
        if (filter == null || filter.isEmpty()) return true;

        if (filter.hasServer() && !filter.server().equals(event.serverName())) {
            return false;
        }

        if (filter.hasPath() && !filter.path().equals(event.path())) {
            return false;
        }

        if (filter.hasTimeRange() && !matchesTimeRange(event, filter)) {
            return false;
        }

        if (filter.hasSearch() && !matchesSearch(event, filter.search())) {
            return false;
        }

        if (filter.hasKeywords() && !matchesKeywords(event, filter.keywordTerms(), filter.keywordMode())) {
            return false;
        }

        return true;
    }

    private boolean matchesTimeRange(LogEvent event, ClientFilter filter) {
        Duration window = "custom".equals(filter.timeRange())
                ? (filter.timeRangeMs() > 0 ? Duration.ofMillis(filter.timeRangeMs()) : null)
                : TIME_RANGES.get(filter.timeRange());
        if (window == null) return true;
        try {
            Instant eventTime = Instant.parse(event.timestamp());
            return eventTime.isAfter(Instant.now().minus(window));
        } catch (Exception e) {
            log.warn("Unparseable timestamp '{}' in event from server '{}' — letting event through",
                    event.timestamp(), event.serverName());
            return true;
        }
    }

    private boolean matchesSearch(LogEvent event, String search) {
        return safeLower(event.message()).contains(search.toLowerCase());
    }

    private boolean matchesKeywords(LogEvent event, List<String> terms, String mode) {
        // terms are already lowercased by ClientFilter.sanitize()
        String haystack = nullToEmpty(event.message()).toLowerCase();
        if ("and".equals(mode)) {
            return terms.stream().allMatch(haystack::contains);
        }
        return terms.stream().anyMatch(haystack::contains);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeLower(String value) {
        return nullToEmpty(value).toLowerCase();
    }
}
