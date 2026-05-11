package org.munycha.logstream.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.config.LogstreamProperties;
import org.munycha.logstream.model.ClientFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        WebSocketSession registeredSession = sessionRegistry.add(session);
        try {
            Map<String, Object> topicMessage = new LinkedHashMap<>();
            topicMessage.put("type", "topics");
            topicMessage.put("topics", properties.getTopics());
            sendControlMessage(registeredSession, topicMessage);
        } catch (Exception e) {
            log.error("Failed to send topic list to session {}", session.getId(), e);
            sessionRegistry.remove(session);
            closeQuietly(session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String action = node.path("action").asText("");

            switch (action) {
                case "subscribe" -> handleSubscribe(session, node);
                case "filter" -> handleFilter(session, node);
                case "clear-filters" -> handleClearFilters(session);
                default -> log.debug("Unknown action '{}' from session {}", action, session.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to parse message from session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void handleSubscribe(WebSocketSession session, JsonNode node) {
        JsonNode topicsNode = node.path("topics");
        if (topicsNode.isArray()) {
            Set<String> topics = new HashSet<>();
            Set<String> allowedTopics = new HashSet<>(properties.getTopics());
            topicsNode.forEach(t -> {
                String topic = t.asText("").trim();
                if (!topic.isEmpty() && allowedTopics.contains(topic)) {
                    topics.add(topic);
                }
            });
            sessionRegistry.subscribe(session, topics);
            log.info("Session {} subscribed to topics: {}", session.getId(), topics);
        }
    }

    private void handleFilter(WebSocketSession session, JsonNode node) {
        JsonNode f = node.path("filters");

        String server = textOrNull(f, "server");
        String path = textOrNull(f, "path");
        String search = textOrNull(f, "search");
        String timeRange = f.path("timeRange").asText("all");
        long timeRangeMs = f.path("timeRangeMs").asLong(0);

        List<String> keywordTerms = new ArrayList<>();
        JsonNode kw = f.path("keywords");
        if (kw.isObject()) {
            JsonNode terms = kw.path("terms");
            if (terms.isArray()) {
                terms.forEach(t -> {
                    String text = t.asText("").trim().toLowerCase(Locale.ROOT);
                    if (!text.isEmpty()) keywordTerms.add(text);
                });
            }
        }
        String keywordMode = kw.path("mode").asText("or");

        ClientFilter filter = ClientFilter.sanitize(server, path, search, keywordTerms, keywordMode, timeRange, timeRangeMs);
        sessionRegistry.setFilter(session, filter);
        sendFilterAck(session, filter);

        log.debug("Session {} updated filter: {}", session.getId(), filter);
    }

    private void handleClearFilters(WebSocketSession session) {
        sessionRegistry.setFilter(session, ClientFilter.EMPTY);
        sendFilterAck(session, ClientFilter.EMPTY);
        log.debug("Session {} cleared filters", session.getId());
    }

    private static String textOrNull(JsonNode parent, String field) {
        String val = parent.path(field).asText(null);
        return (val != null && !val.isBlank()) ? val : null;
    }

    private void sendFilterAck(WebSocketSession session, ClientFilter filter) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "filter-ack");
            message.put("filters", filter);
            sendControlMessage(sessionRegistry.resolve(session), message);
        } catch (Exception e) {
            log.warn("Failed to send filter ack to session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void sendControlMessage(WebSocketSession session, Map<String, Object> message) throws Exception {
        TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(textMessage);
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception ignored) {
            // The session is already unusable.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket disconnected: {} status={}", session.getId(), status);
        sessionRegistry.remove(session);
    }
}
