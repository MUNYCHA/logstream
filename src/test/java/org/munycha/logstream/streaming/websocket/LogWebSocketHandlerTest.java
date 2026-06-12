package org.munycha.logstream.streaming.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.broadcast.ReplayBuffer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogWebSocketHandlerTest {

    private JwtDecoder jwtDecoder;
    private LogWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        LogstreamProperties properties = new LogstreamProperties();
        jwtDecoder = mock(JwtDecoder.class);
        handler = new LogWebSocketHandler(
                new WebSocketSessionRegistry(properties),
                properties,
                new ObjectMapper(),
                new ReplayBuffer(properties),
                jwtDecoder);
    }

    private WebSocketSession mockSession(String id, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private Jwt jwt(String subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private void sendRefresh(WebSocketSession session, String token) {
        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"refresh\",\"token\":\"" + token + "\"}"));
    }

    @Test
    void refreshWithSameSubjectReplacesStoredJwt() {
        Jwt renewed = jwt("alice");
        when(jwtDecoder.decode("new-token")).thenReturn(renewed);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("subject", "alice");
        WebSocketSession session = mockSession("s1", attributes);

        sendRefresh(session, "new-token");

        assertSame(renewed, attributes.get("jwt"));
    }

    @Test
    void refreshForDifferentSubjectIsIgnored() {
        when(jwtDecoder.decode("mallory-token")).thenReturn(jwt("mallory"));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("subject", "alice");
        Jwt original = jwt("alice");
        attributes.put("jwt", original);
        WebSocketSession session = mockSession("s1", attributes);

        sendRefresh(session, "mallory-token");

        assertSame(original, attributes.get("jwt"));
    }

    @Test
    void refreshOnSessionWithoutSubjectIsRejected() {
        when(jwtDecoder.decode("any-token")).thenReturn(jwt("alice"));
        // No "subject" attribute — must fail closed, never adopt the token's identity.
        Map<String, Object> attributes = new HashMap<>();
        WebSocketSession session = mockSession("s1", attributes);

        sendRefresh(session, "any-token");

        assertEquals(null, attributes.get("jwt"));
    }

    @Test
    void invalidRefreshTokenIsIgnored() {
        when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("invalid signature"));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("subject", "alice");
        WebSocketSession session = mockSession("s1", attributes);

        sendRefresh(session, "bad-token");

        assertEquals(null, attributes.get("jwt"));
    }

    @Test
    void blankRefreshTokenNeverHitsTheDecoder() {
        WebSocketSession session = mockSession("s1", new HashMap<>());
        handler.handleTextMessage(session, new TextMessage("{\"action\":\"refresh\",\"token\":\"\"}"));
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void refreshKeepsSessionAheadOfTheExpirySweep() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(new LogstreamProperties());
        LogstreamProperties properties = new LogstreamProperties();
        LogWebSocketHandler wiredHandler = new LogWebSocketHandler(
                registry, properties, new ObjectMapper(), new ReplayBuffer(properties), jwtDecoder);
        SessionExpirySweeper sweeper = new SessionExpirySweeper(registry);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("subject", "alice");
        attributes.put("jwt", Jwt.withTokenValue("stale")
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(Instant.now().minusSeconds(60))
                .build());
        WebSocketSession session = mockSession("s1", attributes);
        registry.add(session, "alice");

        when(jwtDecoder.decode("renewed")).thenReturn(jwt("alice"));
        wiredHandler.handleTextMessage(session,
                new TextMessage("{\"action\":\"refresh\",\"token\":\"renewed\"}"));
        sweeper.closeExpiredSessions();

        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }
}
