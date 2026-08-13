package org.munycha.logstream.streaming.redis;

public record LogEvent(String serverName, String path, String channel, String timestamp, String message) {

    public boolean isValid() {
        return hasText(serverName)
                && hasText(path)
                && hasText(channel)
                && hasText(timestamp)
                && message != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
