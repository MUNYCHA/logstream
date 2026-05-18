package org.munycha.logstream.streaming.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.streaming.websocket.WebSocketSessionRegistry;
import org.munycha.logstream.streaming.websocket.dto.StatsMessage;
import org.munycha.logstream.streaming.websocket.dto.TopicStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Emits lightweight per-topic stats (event rate + active servers) to all connected
 * sessions on a fixed interval — independent of subscription state. Powers UI metadata.
 */
@Component
public class StatsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StatsBroadcaster.class);
    private static final long STATS_INTERVAL_MS = 2000;

    private final StatsAccumulator accumulator;
    private final WebSocketSessionRegistry sessionRegistry;
    private final SessionBackpressure backpressure;
    private final ObjectMapper objectMapper;

    public StatsBroadcaster(StatsAccumulator accumulator,
                            WebSocketSessionRegistry sessionRegistry,
                            SessionBackpressure backpressure,
                            ObjectMapper objectMapper) {
        this.accumulator = accumulator;
        this.sessionRegistry = sessionRegistry;
        this.backpressure = backpressure;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = STATS_INTERVAL_MS)
    public void flushStats() {
        StatsAccumulator.Snapshot snapshot = accumulator.drain();
        if (snapshot.isEmpty()) return;

        try {
            Map<String, TopicStat> topicStats = new LinkedHashMap<>();
            Set<String> allTopics = new HashSet<>(snapshot.counts().keySet());
            allTopics.addAll(snapshot.servers().keySet());

            for (String topic : allTopics) {
                LongAdder counter = snapshot.counts().get(topic);
                Set<String> serverSet = snapshot.servers().get(topic);
                topicStats.put(topic, new TopicStat(
                        counter != null ? counter.sum() : 0,
                        serverSet != null ? serverSet : Set.of()
                ));
            }

            StatsMessage message = new StatsMessage(topicStats, STATS_INTERVAL_MS);
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));

            sessionRegistry.forEach(session -> backpressure.send(session, textMessage));
        } catch (Exception e) {
            log.error("Failed to flush stats: {}", e.getMessage(), e);
        }
    }
}
