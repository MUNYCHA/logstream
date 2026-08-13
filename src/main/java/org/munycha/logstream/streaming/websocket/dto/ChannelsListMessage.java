package org.munycha.logstream.streaming.websocket.dto;

import java.util.List;

/**
 * Greeting sent once on connection with the configured channel list.
 * Wire shape: {@code {"type":"channels","channels":[...]}}.
 */
public record ChannelsListMessage(String type, List<String> channels) {
    public ChannelsListMessage(List<String> channels) {
        this("channels", channels);
    }
}
