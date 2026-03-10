package org.munycha.logstream.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.filter.LogFilterEngine;
import org.munycha.logstream.model.ClientFilter;
import org.munycha.logstream.model.LogEvent;
import org.munycha.logstream.websocket.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LogBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcastService.class);

    /** Max queued messages per session before dropping — prevents slow-client backpressure. */
    private static final int MAX_PENDING_PER_SESSION = 500;

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final LogFilterEngine filterEngine;

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

    @Async
    public void broadcast(LogEvent event) {
        try {
            // Serialize once, reuse for all matching sessions
            final TextMessage[] holder = { null };

            sessionRegistry.forEach(session -> {
                // Skip sessions that have subscriptions but aren't subscribed to this topic
                if (sessionRegistry.hasSubscriptions(session) && !sessionRegistry.isSubscribed(session, event.topic())) {
                    return;
                }

                // Apply per-session filter
                ClientFilter filter = sessionRegistry.getFilter(session);
                if (!filterEngine.matches(event, filter)) {
                    return;
                }

                // Backpressure check — atomic increment-if-under-limit
                AtomicInteger pending = sendQueue.computeIfAbsent(session.getId(), k -> new AtomicInteger(0));
                int current;
                do {
                    current = pending.get();
                    if (current >= MAX_PENDING_PER_SESSION) {
                        log.debug("Dropping message for slow session {} (pending: {})", session.getId(), current);
                        return;
                    }
                } while (!pending.compareAndSet(current, current + 1));

                // Lazy serialize — only if at least one session passes filters
                if (holder[0] == null) {
                    try {
                        holder[0] = new TextMessage(objectMapper.writeValueAsString(event));
                    } catch (Exception e) {
                        log.error("Failed to serialize log event: {}", event, e);
                        pending.decrementAndGet(); // roll back the increment
                        return;
                    }
                }

                trySend(session, holder[0], pending);
            });
        } catch (Exception e) {
            log.error("Failed to broadcast log event: {}", event, e);
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
