package org.munycha.logstream.streaming.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.broadcast.ReplayBuffer;
import org.munycha.logstream.streaming.filter.ClientFilter;
import org.munycha.logstream.streaming.kafka.LogEvent;
import org.munycha.logstream.streaming.websocket.dto.TopicsListMessage;
import org.munycha.logstream.streaming.websocket.dto.WsClientMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    @Override
    public List<String> getSubProtocols() {
        // Browser offers ['logstream.v1', 'bearer.<jwt>']. Spring echoes back 'logstream.v1'
        // (the protocol the server actually advertises); the bearer entry is consumed by
        // JwtHandshakeInterceptor and never echoed.
        return List.of("logstream.v1");
    }

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    /** Matches LogBroadcastService.MAX_BATCH_SIZE — same frame shape as live batches. */
    private static final int REPLAY_CHUNK_SIZE = 100;

    private final WebSocketSessionRegistry sessionRegistry;
    private final LogstreamProperties properties;
    private final ObjectMapper objectMapper;
    private final ReplayBuffer replayBuffer;
    private final JwtDecoder jwtDecoder;

    public LogWebSocketHandler(WebSocketSessionRegistry sessionRegistry,
                               LogstreamProperties properties,
                               ObjectMapper objectMapper,
                               ReplayBuffer replayBuffer,
                               JwtDecoder jwtDecoder) {
        this.sessionRegistry = sessionRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.replayBuffer = replayBuffer;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Subject is stashed in handshake attributes by JwtHandshakeInterceptor.
        String subject = (String) session.getAttributes().get("subject");
        WebSocketSession managed = sessionRegistry.add(session, subject);
        if (managed == null) {
            log.warn("Rejecting session {}: user '{}' reached the per-user session limit ({})",
                    session.getId(), subject, properties.getMaxSessionsPerUser());
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Session limit reached"));
            return;
        }
        log.info("WebSocket connected: {} (active sessions: {})", session.getId(), sessionRegistry.activeCount());
        // Send the greeting through the decorated session so it can't collide
        // with a concurrent broadcast on the raw socket.
        try {
            TopicsListMessage greeting = new TopicsListMessage(properties.getTopics());
            managed.sendMessage(new TextMessage(objectMapper.writeValueAsString(greeting)));
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
            } else if (msg instanceof WsClientMessage.Refresh refresh) {
                handleRefresh(session, refresh);
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
        // Subscribe before replaying so no live event is lost in between; the rare
        // event that gets both broadcast and replayed in that window is acceptable.
        Set<String> previous = sessionRegistry.subscribe(session, topics);
        log.info("Session {} subscribed to topics: {}", session.getId(), topics);

        Set<String> newlySubscribed = new HashSet<>(topics);
        newlySubscribed.removeAll(previous);
        sendReplay(session, newlySubscribed);
    }

    /**
     * Sends buffered history for newly subscribed topics so the client sees recent
     * logs immediately instead of a blank panel. Replay is unfiltered — the UI
     * filters client-side, and server-side filters usually arrive after subscribe.
     */
    private void sendReplay(WebSocketSession session, Set<String> topics) {
        if (topics.isEmpty()) return;
        List<LogEvent> events = replayBuffer.replayFor(topics);
        if (events.isEmpty()) return;
        WebSocketSession managed = sessionRegistry.getManaged(session);
        if (managed == null) return;
        try {
            for (int start = 0; start < events.size(); start += REPLAY_CHUNK_SIZE) {
                int end = Math.min(start + REPLAY_CHUNK_SIZE, events.size());
                List<LogEvent> chunk = events.subList(start, end);
                String json = chunk.size() == 1
                        ? objectMapper.writeValueAsString(chunk.get(0))
                        : objectMapper.writeValueAsString(chunk);
                managed.sendMessage(new TextMessage(json));
            }
            log.debug("Replayed {} buffered events to session {} for topics {}",
                    events.size(), session.getId(), topics);
        } catch (Exception e) {
            log.warn("Failed to send replay to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * Accepts a renewed access token pushed over the socket and updates the session's
     * stored JWT, keeping it ahead of {@link SessionExpirySweeper}. The new token must
     * belong to the same subject that authenticated the handshake — a token for anyone
     * else is ignored, so a session can never migrate to another user.
     */
    private void handleRefresh(WebSocketSession session, WsClientMessage.Refresh refresh) {
        if (refresh.token() == null || refresh.token().isBlank()) return;
        try {
            Jwt jwt = jwtDecoder.decode(refresh.token());
            String subject = (String) session.getAttributes().get("subject");
            // Fail closed: a session with no subject on record has no identity to
            // verify against, so it must not be refreshable with anyone's token.
            if (subject == null || !subject.equals(jwt.getSubject())) {
                log.warn("Session {} sent a refresh token for a different subject — ignored", session.getId());
                return;
            }
            session.getAttributes().put("jwt", jwt);
            log.debug("Session {} refreshed its token, new expiry {}", session.getId(), jwt.getExpiresAt());
        } catch (JwtException e) {
            log.warn("Session {} sent an invalid refresh token: {}", session.getId(), e.getMessage());
        }
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
