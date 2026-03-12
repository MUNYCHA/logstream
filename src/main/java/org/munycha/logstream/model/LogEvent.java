package org.munycha.logstream.model;

public record LogEvent(String serverName, String path, String topic, String timestamp, String message) {

    public boolean isValid() {
        return hasText(serverName)
                && hasText(path)
                && hasText(topic)
                && hasText(timestamp)
                && message != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
