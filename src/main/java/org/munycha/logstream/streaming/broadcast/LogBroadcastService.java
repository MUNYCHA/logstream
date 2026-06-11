package org.munycha.logstream.streaming.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.streaming.filter.ClientFilter;
import org.munycha.logstream.streaming.filter.LogFilterEngine;
import org.munycha.logstream.streaming.kafka.LogEvent;
import org.munycha.logstream.streaming.topic.TopicMetaStore;
import org.munycha.logstream.streaming.websocket.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hot path: enqueues incoming Kafka events and flushes them to subscribed sessions
 * every 100ms. Per-session filtering happens here; stats and backpressure are delegated.
 */
@Service
public class LogBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcastService.class);

    /** Max queued events waiting for flush before oldest events are dropped. */
    private static final int MAX_INCOMING_QUEUE = 20_000;

    /** Max events to batch into a single WebSocket message. */
    private static final int MAX_BATCH_SIZE = 100;

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final LogFilterEngine filterEngine;
    private final TopicMetaStore metaStore;
    private final StatsAccumulator statsAccumulator;
    private final SessionBackpressure sessionBackpressure;

    private final ConcurrentLinkedQueue<LogEvent> incomingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingQueueSize = new AtomicInteger(0);

    public LogBroadcastService(WebSocketSessionRegistry sessionRegistry,
                               ObjectMapper objectMapper,
                               LogFilterEngine filterEngine,
                               TopicMetaStore metaStore,
                               StatsAccumulator statsAccumulator,
                               SessionBackpressure sessionBackpressure) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.filterEngine = filterEngine;
        this.metaStore = metaStore;
        this.statsAccumulator = statsAccumulator;
        this.sessionBackpressure = sessionBackpressure;
    }

    /**
     * Called from Kafka consumer — enqueues the event for batched broadcast.
     * Non-blocking so it never stalls the Kafka consumer thread.
     */
    public void broadcast(LogEvent event) {
        if (event == null || !event.isValid()) {
            log.debug("Dropping invalid log event: {}", event);
            return;
        }

        while (true) {
            int currentSize = incomingQueueSize.get();
            if (currentSize < MAX_INCOMING_QUEUE) {
                if (incomingQueueSize.compareAndSet(currentSize, currentSize + 1)) {
                    incomingQueue.add(event);
                    return;
                }
                continue;
            }

            LogEvent dropped = incomingQueue.poll();
            if (dropped != null) {
                incomingQueueSize.decrementAndGet();
            } else {
                incomingQueueSize.compareAndSet(currentSize, 0);
            }
        }
    }

    /**
     * Flushes queued events every 100ms. Sessions are grouped by identical
     * (subscriptions, filter) so each group's events are matched and serialized
     * exactly once — with the UI auto-subscribing every client to all topics,
     * additional unfiltered viewers cost one extra send, not one extra serialization.
     */
    @Scheduled(fixedDelay = 100)
    public void flush() {
        List<LogEvent> batch = new ArrayList<>(MAX_BATCH_SIZE * 2);
        LogEvent event;
        while ((event = incomingQueue.poll()) != null) {
            incomingQueueSize.decrementAndGet();
            batch.add(event);
        }
        if (batch.isEmpty()) return;

        // Stats + topic metadata are independent of subscriptions — record every event.
        for (LogEvent evt : batch) {
            statsAccumulator.record(evt);
            metaStore.record(evt);
        }

        try {
            Map<DispatchKey, List<WebSocketSession>> groups = new HashMap<>();
            sessionRegistry.forEach(session -> {
                Set<String> topics = sessionRegistry.getSubscriptions(session);
                // Require explicit subscription — no logs until client subscribes
                if (topics == null || topics.isEmpty()) return;
                ClientFilter filter = sessionRegistry.getFilter(session);
                // Copy the live subscription set: the key must not mutate under a
                // concurrent re-subscribe while it sits in the HashMap.
                groups.computeIfAbsent(new DispatchKey(Set.copyOf(topics), filter), k -> new ArrayList<>())
                        .add(session);
            });
            groups.forEach((key, sessions) -> dispatchToGroup(key, sessions, batch));
        } catch (Exception e) {
            log.error("Failed to flush broadcast batch: {}", e.getMessage(), e);
        }
    }

    private void dispatchToGroup(DispatchKey key, List<WebSocketSession> sessions, List<LogEvent> batch) {
        List<LogEvent> matched = new ArrayList<>();
        for (LogEvent evt : batch) {
            if (!key.topics().contains(evt.topic())) continue;
            if (!filterEngine.matches(evt, key.filter())) continue;
            matched.add(evt);
        }
        if (matched.isEmpty()) return;

        for (int start = 0; start < matched.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, matched.size());
            List<LogEvent> chunk = matched.subList(start, end);
            try {
                String json = chunk.size() == 1
                        ? objectMapper.writeValueAsString(chunk.get(0))
                        : objectMapper.writeValueAsString(chunk);
                // TextMessage is immutable — safe to share across the group's sessions.
                TextMessage message = new TextMessage(json);
                for (WebSocketSession session : sessions) {
                    sessionBackpressure.send(session, message);
                }
            } catch (Exception e) {
                log.error("Failed to serialize batch for group of {} session(s): {}",
                        sessions.size(), e.getMessage());
            }
        }
    }

    /** Sessions with equal subscriptions and filter share one serialized payload. */
    private record DispatchKey(Set<String> topics, ClientFilter filter) {}
}
