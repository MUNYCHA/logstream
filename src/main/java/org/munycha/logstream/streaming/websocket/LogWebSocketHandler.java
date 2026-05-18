package org.munycha.logstream.streaming.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.filter.ClientFilter;
import org.munycha.logstream.streaming.websocket.dto.TopicsListMessage;
import org.munycha.logstream.streaming.websocket.dto.WsClientMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
            TopicsListMessage greeting = new TopicsListMessage(properties.getTopics());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(greeting)));
        } catch (Exception e) {
            log.error("Failed to send topic list to session {}", session.getId(), e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            WsClientMessage msg = objectMapper.readValue(message.getPayload(), WsClientMessage.class);
            if (msg instanceof WsClientMessage.Subscribe sub) {
                handleSubscribe(session, sub);
            } else if (msg instanceof WsClientMessage.Filter f) {
                handleFilter(session, f);
            } else if (msg instanceof WsClientMessage.ClearFilters) {
                handleClearFilters(session);
            }
        } catch (Exception e) {
            log.warn("Failed to parse message from session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void handleSubscribe(WebSocketSession session, WsClientMessage.Subscribe sub) {
        if (sub.topics() == null) return;
        Set<String> allowedTopics = new HashSet<>(properties.getTopics());
        Set<String> topics = sub.topics().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .filter(allowedTopics::contains)
                .collect(Collectors.toSet());
        sessionRegistry.subscribe(session, topics);
        log.info("Session {} subscribed to topics: {}", session.getId(), topics);
    }

    private void handleFilter(WebSocketSession session, WsClientMessage.Filter f) {
        ClientFilter filter = (f.filters() != null) ? f.filters().toClientFilter() : ClientFilter.EMPTY;
        sessionRegistry.setFilter(session, filter);
        log.debug("Session {} updated filter: {}", session.getId(), filter);
    }

    private void handleClearFilters(WebSocketSession session) {
        sessionRegistry.setFilter(session, ClientFilter.EMPTY);
        log.debug("Session {} cleared filters", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket disconnected: {} status={}", session.getId(), status);
        sessionRegistry.remove(session);
    }
}
