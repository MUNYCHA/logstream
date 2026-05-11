package org.munycha.logstream.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.filter.LogFilterEngine;
import org.munycha.logstream.model.ClientFilter;
import org.munycha.logstream.model.LogEvent;
import org.munycha.logstream.websocket.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Service
public class LogBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcastService.class);

    /** Max queued messages per session before dropping — prevents slow-client backpressure. */
    private static final int MAX_PENDING_PER_SESSION = 500;

    /** Max queued events waiting for flush before oldest events are dropped. */
    private static final int MAX_INCOMING_QUEUE = 20_000;

    /** Max events to batch into a single WebSocket message. */
    private static final int MAX_BATCH_SIZE = 100;

    /** Max estimated payload size for one WebSocket frame. */
    private static final int MAX_PAYLOAD_CHARS = 384 * 1024;

    /** Max characters retained from a single log message before broadcast. */
    private static final int MAX_MESSAGE_CHARS = 64 * 1024;

    private static final String TRUNCATED_SUFFIX = "... [truncated]";

    /** Stats broadcast interval in ms. */
    private static final long STATS_INTERVAL_MS = 2000;

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final LogFilterEngine filterEngine;
    private final TopicMetaStore metaStore;

    /** Per-topic event count accumulator for stats broadcast. */
    private final AtomicReference<ConcurrentHashMap<String, LongAdder>> statsCounters =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /** Per-topic active servers accumulator for stats broadcast. */
    private final AtomicReference<ConcurrentHashMap<String, Set<String>>> statsServers =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /** Incoming events queue — drained every flush interval. */
    private final ConcurrentLinkedQueue<LogEvent> incomingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingQueueSize = new AtomicInteger(0);

    /** Tracks in-flight send count per session to detect slow clients. */
    private final ConcurrentHashMap<String, AtomicInteger> sendQueue = new ConcurrentHashMap<>();

    public LogBroadcastService(WebSocketSessionRegistry sessionRegistry,
                               ObjectMapper objectMapper,
                               LogFilterEngine filterEngine,
                               TopicMetaStore metaStore) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.filterEngine = filterEngine;
        this.metaStore = metaStore;
        sessionRegistry.onRemove(this::onSessionRemoved);
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

        LogEvent eventToQueue = truncateIfNeeded(event);

        while (true) {
            int currentSize = incomingQueueSize.get();
            if (currentSize < MAX_INCOMING_QUEUE) {
                if (incomingQueueSize.compareAndSet(currentSize, currentSize + 1)) {
                    incomingQueue.add(eventToQueue);
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
     * Flushes queued events every 100ms, batching per-session filtered events
     * into a single WebSocket message (JSON array) to reduce frame overhead.
     */
    @Scheduled(fixedDelay = 100)
    public void flush() {
        // Drain all queued events
        List<LogEvent> batch = new ArrayList<>(MAX_BATCH_SIZE * 2);
        LogEvent event;
        while ((event = incomingQueue.poll()) != null) {
            incomingQueueSize.decrementAndGet();
            batch.add(event);
        }
        if (batch.isEmpty()) return;

        // Accumulate stats for all events (regardless of subscriptions)
        for (LogEvent evt : batch) {
            statsCounters.get()
                    .computeIfAbsent(evt.topic(), k -> new LongAdder())
                    .increment();
            statsServers.get()
                    .computeIfAbsent(evt.topic(), k -> ConcurrentHashMap.newKeySet())
                    .add(evt.serverName());
            metaStore.record(evt);
        }

        try {
            sessionRegistry.forEach(session -> {
                List<LogEvent> matched = new ArrayList<>();

                for (LogEvent evt : batch) {
                    // Require explicit subscription — no logs until client subscribes
                    if (!sessionRegistry.isSubscribed(session, evt.topic())) {
                        continue;
                    }

                    // Apply per-session filter
                    ClientFilter filter = sessionRegistry.getFilter(session);
                    if (!filterEngine.matches(evt, filter)) {
                        continue;
                    }

                    matched.add(evt);
                }

                if (matched.isEmpty()) {
                    return;
                }

                AtomicInteger pending = sendQueue.computeIfAbsent(session.getId(), k -> new AtomicInteger(0));
                for (List<LogEvent> chunk : chunkForSend(matched)) {
                    if (!reserveSendSlot(session, pending)) {
                        return;
                    }

                    try {
                        String json = chunk.size() == 1
                                ? objectMapper.writeValueAsString(chunk.get(0))
                                : objectMapper.writeValueAsString(chunk);
                        trySend(session, new TextMessage(json), pending);
                    } catch (Exception e) {
                        log.error("Failed to serialize batch for session {}: {}", session.getId(), e.getMessage());
                        pending.decrementAndGet();
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to flush broadcast batch: {}", e.getMessage(), e);
        }
    }

    /**
     * Broadcasts lightweight topic stats (rates + active servers) every 2 seconds
     * for the topics each session explicitly subscribed to.
     */
    @Scheduled(fixedDelay = STATS_INTERVAL_MS)
    public void flushStats() {
        // Atomically swap accumulators so flush() writes to fresh maps
        ConcurrentHashMap<String, LongAdder> counts = statsCounters.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, Set<String>> servers = statsServers.getAndSet(new ConcurrentHashMap<>());

        if (counts.isEmpty() && servers.isEmpty()) return;

        try {
            Map<String, Object> topicStats = new LinkedHashMap<>();
            Set<String> allTopics = new HashSet<>(counts.keySet());
            allTopics.addAll(servers.keySet());

            for (String topic : allTopics) {
                Map<String, Object> entry = new LinkedHashMap<>();
                LongAdder counter = counts.get(topic);
                entry.put("rate", counter != null ? counter.sum() : 0);
                Set<String> serverSet = servers.get(topic);
                entry.put("servers", serverSet != null ? serverSet : Set.of());
                topicStats.put(topic, entry);
            }

            sessionRegistry.forEach(session -> {
                Map<String, Object> visibleTopicStats = new LinkedHashMap<>();
                topicStats.forEach((topic, stats) -> {
                    if (sessionRegistry.isSubscribed(session, topic)) {
                        visibleTopicStats.put(topic, stats);
                    }
                });
                if (visibleTopicStats.isEmpty()) {
                    return;
                }

                try {
                    Map<String, Object> sessionMessage = new LinkedHashMap<>();
                    sessionMessage.put("type", "stats");
                    sessionMessage.put("topics", visibleTopicStats);
                    sessionMessage.put("intervalMs", STATS_INTERVAL_MS);

                    TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(sessionMessage));
                    AtomicInteger pending = sendQueue.computeIfAbsent(session.getId(), k -> new AtomicInteger(0));
                    if (!reserveSendSlot(session, pending)) {
                        return;
                    }
                    trySend(session, textMessage, pending);
                } catch (Exception e) {
                    log.warn("Failed to serialize stats for session {}: {}", session.getId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to flush stats: {}", e.getMessage(), e);
        }
    }

    private LogEvent truncateIfNeeded(LogEvent event) {
        String message = event.message();
        if (message.length() <= MAX_MESSAGE_CHARS) {
            return event;
        }

        int end = Math.max(0, MAX_MESSAGE_CHARS - TRUNCATED_SUFFIX.length());
        return event.withMessage(message.substring(0, end) + TRUNCATED_SUFFIX);
    }

    private List<List<LogEvent>> chunkForSend(List<LogEvent> events) {
        List<List<LogEvent>> chunks = new ArrayList<>();
        List<LogEvent> current = new ArrayList<>();
        int currentSize = 2;

        for (LogEvent event : events) {
            int eventSize = estimatePayloadSize(event);
            boolean wouldExceedCount = current.size() >= MAX_BATCH_SIZE;
            boolean wouldExceedSize = !current.isEmpty() && currentSize + eventSize > MAX_PAYLOAD_CHARS;
            if (wouldExceedCount || wouldExceedSize) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSize = 2;
            }
            current.add(event);
            currentSize += eventSize;
        }

        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private int estimatePayloadSize(LogEvent event) {
        return 128
                + length(event.serverName())
                + length(event.path())
                + length(event.topic())
                + length(event.timestamp())
                + length(event.message());
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private boolean reserveSendSlot(WebSocketSession session, AtomicInteger pending) {
        int current;
        do {
            current = pending.get();
            if (current >= MAX_PENDING_PER_SESSION) {
                log.debug("Dropping message for slow session {} (pending: {})", session.getId(), current);
                return false;
            }
        } while (!pending.compareAndSet(current, current + 1));
        return true;
    }

    private void trySend(WebSocketSession session, TextMessage message, AtomicInteger pending) {
        synchronized (session) {
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.warn("Failed to send to session {}, removing it", session.getId(), e);
                sessionRegistry.remove(session);
                sendQueue.remove(session.getId());
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    /** Called by registry on session disconnect to clean up send queue tracking. */
    public void onSessionRemoved(String sessionId) {
        sendQueue.remove(sessionId);
    }
}
