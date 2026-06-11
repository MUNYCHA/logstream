package org.munycha.logstream.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "logstream")
public class LogstreamProperties {

    private List<String> topics;
    private List<String> allowedOrigins;
    private String logDir;

    /** Max concurrent WebSocket sessions per authenticated user; values <= 0 disable the cap. */
    private int maxSessionsPerUser = 5;

    /** Events kept per topic for replay to newly subscribed sessions; values <= 0 disable replay. */
    private int replayBufferSize = 500;

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

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public int getMaxSessionsPerUser() {
        return maxSessionsPerUser;
    }

    public void setMaxSessionsPerUser(int maxSessionsPerUser) {
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    public int getReplayBufferSize() {
        return replayBufferSize;
    }

    public void setReplayBufferSize(int replayBufferSize) {
        this.replayBufferSize = replayBufferSize;
    }
}
