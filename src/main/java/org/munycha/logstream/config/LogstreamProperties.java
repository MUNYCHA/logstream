package org.munycha.logstream.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

@Component
@ConfigurationProperties(prefix = "logstream")
public class LogstreamProperties {

    private List<String> topics = List.of();
    private List<String> allowedOrigins = List.of();
    private String logDir;
    private String authToken;

    @PostConstruct
    void validate() {
        if (topics.isEmpty()) {
            throw new IllegalStateException("logstream.topics must contain at least one topic");
        }
        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException("logstream.allowed-origins must contain at least one origin");
        }
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = normalizeList(topics);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = normalizeList(allowedOrigins);
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        String trimmed = authToken == null ? null : authToken.trim();
        this.authToken = trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isAuthEnabled() {
        return authToken != null && !authToken.isBlank();
    }

    public boolean matchesAuthToken(String candidate) {
        if (!isAuthEnabled() || candidate == null) {
            return false;
        }
        byte[] expected = authToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
