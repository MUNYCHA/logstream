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

@Service
public class LogBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcastService.class);

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final LogFilterEngine filterEngine;

    public LogBroadcastService(WebSocketSessionRegistry sessionRegistry,
                               ObjectMapper objectMapper,
                               LogFilterEngine filterEngine) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.filterEngine = filterEngine;
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

                // Lazy serialize — only if at least one session passes filters
                if (holder[0] == null) {
                    try {
                        holder[0] = new TextMessage(objectMapper.writeValueAsString(event));
                    } catch (Exception e) {
                        log.error("Failed to serialize log event: {}", event, e);
                        return;
                    }
                }

                synchronized (session) {
                    try {
                        session.sendMessage(holder[0]);
                    } catch (Exception e) {
                        log.warn("Failed to send to session {}, removing it", session.getId(), e);
                        sessionRegistry.remove(session);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to broadcast log event: {}", event, e);
        }
    }
}
