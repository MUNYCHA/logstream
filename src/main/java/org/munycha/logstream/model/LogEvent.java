package org.munycha.logstream.model;

public record LogEvent(String serverName, String path, String topic, String timestamp, String message) {}
