package org.munycha.logstream.streaming.websocket.dto;

import java.util.List;

/**
 * Greeting sent once on connection with the configured topic list.
 * Wire shape: {@code {"type":"topics","topics":[...]}}.
 */
public record TopicsListMessage(String type, List<String> topics) {
    public TopicsListMessage(List<String> topics) {
        this("topics", topics);
    }
}
