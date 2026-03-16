package org.munycha.logstream.filter;

import org.munycha.logstream.model.ClientFilter;
import org.munycha.logstream.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Max regex pattern length to prevent catastrophic backtracking DoS. */
    private static final int MAX_REGEX_LENGTH = 512;
    private static final int REGEX_CACHE_LIMIT = 256;

    /** Cache compiled regex patterns — evicted when filter changes (new pattern = new entry). */
    private final Map<String, Pattern> regexCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > REGEX_CACHE_LIMIT;
                }
            }
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

        if (filter.hasSearch() && !matchesSearch(event, filter.search(), filter.regex())) {
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

    private boolean matchesSearch(LogEvent event, String search, boolean isRegex) {
        if (isRegex) {
            return matchesRegex(event, search);
        }
        String lower = search.toLowerCase();
        return safeLower(event.message()).contains(lower)
                || safeLower(event.serverName()).contains(lower)
                || safeLower(event.path()).contains(lower);
    }

    private boolean matchesRegex(LogEvent event, String pattern) {
        if (pattern.length() > MAX_REGEX_LENGTH) {
            // Reject oversized patterns — drop the event to signal the filter is active but invalid
            return false;
        }

        Pattern compiled;
        synchronized (regexCache) {
            compiled = regexCache.get(pattern);
            if (compiled == null && !regexCache.containsKey(pattern)) {
                try {
                    compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    compiled = null;
                }
                regexCache.put(pattern, compiled);
            }
        }

        if (compiled == null) {
            // Invalid regex — drop event (filter-ack already notified client of the error)
            return false;
        }

        return compiled.matcher(nullToEmpty(event.message())).find()
                || compiled.matcher(nullToEmpty(event.serverName())).find()
                || compiled.matcher(nullToEmpty(event.path())).find();
    }

    private boolean matchesKeywords(LogEvent event, List<String> terms, String mode) {
        String haystack = (nullToEmpty(event.message()) + "\0" + nullToEmpty(event.serverName()) + "\0" + nullToEmpty(event.path())).toLowerCase();
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
        if (pattern.length() > MAX_REGEX_LENGTH) {
            return "Pattern too long (max " + MAX_REGEX_LENGTH + " characters)";
        }
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeLower(String value) {
        return nullToEmpty(value).toLowerCase();
    }
}
