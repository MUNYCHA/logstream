package org.munycha.logstream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "logstream")
public class LogstreamProperties {

    private List<String> topics;
    private List<String> allowedOrigins;
    private Map<String, String> logFiles;

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Map<String, String> getLogFiles() {
        return logFiles;
    }

    public void setLogFiles(Map<String, String> logFiles) {
        this.logFiles = logFiles;
    }
}
