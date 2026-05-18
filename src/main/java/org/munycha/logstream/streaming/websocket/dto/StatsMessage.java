package org.munycha.logstream.streaming.websocket.dto;

import java.util.Map;

/**
 * Periodic stats broadcast — fired on a fixed interval to every connected session
 * regardless of subscription state.
 * Wire shape: {@code {"type":"stats","topics":{topic:{rate,servers}},"intervalMs":2000}}.
 */
public record StatsMessage(String type, Map<String, TopicStat> topics, long intervalMs) {
    public StatsMessage(Map<String, TopicStat> topics, long intervalMs) {
        this("stats", topics, intervalMs);
    }
}
