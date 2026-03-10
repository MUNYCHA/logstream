package org.munycha.logstream.filter;

import org.munycha.logstream.model.ClientFilter;
import org.munycha.logstream.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

        if (filter.hasTimeRange() && !matchesTimeRange(event, filter.timeRange())) {
            return false;
        }

        if (filter.hasSearch() && !matchesSearch(event, filter.search(), filter.regex())) {
            return false;
        }

        if (filter.hasKeywords() && !matchesKeywords(event, filter.keywordTerms(), filter.keywordMode())) {
            return false;
        }

        return true;
    }

    private boolean matchesTimeRange(LogEvent event, String timeRange) {
        Duration window = TIME_RANGES.get(timeRange);
        if (window == null) return true;
        try {
            Instant eventTime = Instant.parse(event.timestamp());
            return eventTime.isAfter(Instant.now().minus(window));
        } catch (Exception e) {
            // If timestamp can't be parsed, let it through
            return true;
        }
    }

    private boolean matchesSearch(LogEvent event, String search, boolean isRegex) {
        if (isRegex) {
            return matchesRegex(event, search);
        }
        String lower = search.toLowerCase();
        return event.message().toLowerCase().contains(lower)
                || event.serverName().toLowerCase().contains(lower)
                || event.path().toLowerCase().contains(lower);
    }

    private boolean matchesRegex(LogEvent event, String pattern) {
        try {
            Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return compiled.matcher(event.message()).find()
                    || compiled.matcher(event.serverName()).find()
                    || compiled.matcher(event.path()).find();
        } catch (PatternSyntaxException e) {
            // Invalid regex — skip filtering, server already sent error on filter-ack
            return true;
        }
    }

    private boolean matchesKeywords(LogEvent event, java.util.List<String> terms, String mode) {
        String haystack = event.message().toLowerCase();
        if ("and".equals(mode)) {
            return terms.stream().allMatch(kw -> haystack.contains(kw.toLowerCase()));
        }
        // default "or"
        return terms.stream().anyMatch(kw -> haystack.contains(kw.toLowerCase()));
    }

    /**
     * Validates a regex pattern. Returns null if valid, error message if invalid.
     */
    public String validateRegex(String pattern) {
        if (pattern == null || pattern.isBlank()) return null;
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }
}
