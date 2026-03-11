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

    /** Max events to batch into a single WebSocket message. */
    private static final int MAX_BATCH_SIZE = 100;

    /** Stats broadcast interval in ms. */
    private static final long STATS_INTERVAL_MS = 2000;

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final LogFilterEngine filterEngine;

    /** Per-topic event count accumulator for stats broadcast. */
    private final AtomicReference<ConcurrentHashMap<String, LongAdder>> statsCounters =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /** Per-topic active servers accumulator for stats broadcast. */
    private final AtomicReference<ConcurrentHashMap<String, Set<String>>> statsServers =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /** Incoming events queue — drained every flush interval. */
    private final ConcurrentLinkedQueue<LogEvent> incomingQueue = new ConcurrentLinkedQueue<>();

    /** Tracks in-flight send count per session to detect slow clients. */
    private final ConcurrentHashMap<String, AtomicInteger> sendQueue = new ConcurrentHashMap<>();

    public LogBroadcastService(WebSocketSessionRegistry sessionRegistry,
                               ObjectMapper objectMapper,
                               LogFilterEngine filterEngine) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.filterEngine = filterEngine;
        sessionRegistry.onRemove(this::onSessionRemoved);
    }

    /**
     * Called from Kafka consumer — enqueues the event for batched broadcast.
     * Non-blocking so it never stalls the Kafka consumer thread.
     */
    public void broadcast(LogEvent event) {
        incomingQueue.add(event);
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

                if (matched.isEmpty()) return;

                // Backpressure check
                AtomicInteger pending = sendQueue.computeIfAbsent(session.getId(), k -> new AtomicInteger(0));
                int current;
                do {
                    current = pending.get();
                    if (current >= MAX_PENDING_PER_SESSION) {
                        log.debug("Dropping batch for slow session {} (pending: {})", session.getId(), current);
                        return;
                    }
                } while (!pending.compareAndSet(current, current + 1));

                try {
                    // Send as JSON array if multiple events, single object if one
                    String json;
                    if (matched.size() == 1) {
                        json = objectMapper.writeValueAsString(matched.get(0));
                    } else {
                        json = objectMapper.writeValueAsString(matched);
                    }
                    trySend(session, new TextMessage(json), pending);
                } catch (Exception e) {
                    log.error("Failed to serialize batch for session {}: {}", session.getId(), e.getMessage());
                    pending.decrementAndGet();
                }
            });
        } catch (Exception e) {
            log.error("Failed to flush broadcast batch: {}", e.getMessage(), e);
        }
    }

    /**
     * Broadcasts lightweight topic stats (rates + active servers) to ALL connected sessions
     * every 2 seconds — even those with no subscriptions. Powers sidebar metadata.
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

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "stats");
            message.put("topics", topicStats);
            message.put("intervalMs", STATS_INTERVAL_MS);

            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));

            sessionRegistry.forEach(session -> {
                synchronized (session) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (Exception e) {
                        log.warn("Failed to send stats to session {}", session.getId());
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to flush stats: {}", e.getMessage(), e);
        }
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
