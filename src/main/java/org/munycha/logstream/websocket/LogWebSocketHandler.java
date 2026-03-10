package org.munycha.logstream.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.config.LogstreamProperties;
import org.munycha.logstream.filter.LogFilterEngine;
import org.munycha.logstream.model.ClientFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    private final WebSocketSessionRegistry sessionRegistry;
    private final LogstreamProperties properties;
    private final ObjectMapper objectMapper;
    private final LogFilterEngine filterEngine;

    public LogWebSocketHandler(WebSocketSessionRegistry sessionRegistry,
                               LogstreamProperties properties,
                               ObjectMapper objectMapper,
                               LogFilterEngine filterEngine) {
        this.sessionRegistry = sessionRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.filterEngine = filterEngine;
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
            topicsNode.forEach(t -> topics.add(t.asText()));
            sessionRegistry.subscribe(session, topics);
            log.info("Session {} subscribed to topics: {}", session.getId(), topics);
        }
    }

    private void handleFilter(WebSocketSession session, JsonNode node) {
        JsonNode f = node.path("filters");

        String server = textOrNull(f, "server");
        String path = textOrNull(f, "path");
        String search = textOrNull(f, "search");
        boolean regex = f.path("regex").asBoolean(false);
        String timeRange = f.path("timeRange").asText("all");

        List<String> keywordTerms = new ArrayList<>();
        JsonNode kw = f.path("keywords");
        if (kw.isObject()) {
            JsonNode terms = kw.path("terms");
            if (terms.isArray()) {
                terms.forEach(t -> {
                    String text = t.asText("").trim().toLowerCase();
                    if (!text.isEmpty()) keywordTerms.add(text);
                });
            }
        }
        String keywordMode = kw.path("mode").asText("or");

        ClientFilter filter = new ClientFilter(server, path, search, regex, keywordTerms, keywordMode, timeRange);
        sessionRegistry.setFilter(session, filter);

        log.debug("Session {} updated filter: {}", session.getId(), filter);

        // Send filter-ack back to client
        sendFilterAck(session, filter);
    }

    private void handleClearFilters(WebSocketSession session) {
        sessionRegistry.setFilter(session, ClientFilter.EMPTY);
        log.debug("Session {} cleared filters", session.getId());
        sendFilterAck(session, ClientFilter.EMPTY);
    }

    private void sendFilterAck(WebSocketSession session, ClientFilter filter) {
        try {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", "filter-ack");
            ack.put("filters", filter);

            // Include regex validation error if applicable
            if (filter.regex() && filter.hasSearch()) {
                String error = filterEngine.validateRegex(filter.search());
                if (error != null) {
                    ack.put("regexError", error);
                }
            }

            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
            }
        } catch (Exception e) {
            log.warn("Failed to send filter-ack to session {}", session.getId(), e);
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        String val = parent.path(field).asText(null);
        return (val != null && !val.isBlank()) ? val : null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket disconnected: {} status={}", session.getId(), status);
        sessionRegistry.remove(session);
    }
}
