package org.munycha.logstream.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.config.LogstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    private final WebSocketSessionRegistry sessionRegistry;
    private final LogstreamProperties properties;
    private final ObjectMapper objectMapper;

    public LogWebSocketHandler(WebSocketSessionRegistry sessionRegistry,
                               LogstreamProperties properties,
                               ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: {} (active sessions: {})", session.getId(), sessionRegistry.activeCount() + 1);
        sessionRegistry.add(session);
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(properties.getTopics())));
        } catch (Exception e) {
            log.error("Failed to send topic list to session {}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket disconnected: {} status={}", session.getId(), status);
        sessionRegistry.remove(session);
    }
}
