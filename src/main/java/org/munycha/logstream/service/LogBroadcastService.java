package org.munycha.logstream.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public LogBroadcastService(WebSocketSessionRegistry sessionRegistry, ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    @Async
    public void broadcast(LogEvent event) {
        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(event));
            sessionRegistry.forEach(session -> {
                // Skip sessions that have subscriptions but aren't subscribed to this topic
                if (sessionRegistry.hasSubscriptions(session) && !sessionRegistry.isSubscribed(session, event.topic())) {
                    return;
                }
                synchronized (session) {
                    try {
                        session.sendMessage(message);
                    } catch (Exception e) {
                        log.warn("Failed to send to session {}, removing it", session.getId(), e);
                        sessionRegistry.remove(session);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize log event: {}", event, e);
        }
    }
}
