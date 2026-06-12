package org.munycha.logstream.streaming.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionExpirySweeperTest {

    private WebSocketSessionRegistry registry;
    private SessionExpirySweeper sweeper;

    @BeforeEach
    void setUp() {
        registry = new WebSocketSessionRegistry(new LogstreamProperties());
        sweeper = new SessionExpirySweeper(registry);
    }

    private WebSocketSession mockSession(String id, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private Jwt jwt(String subject, Instant expiresAt) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(expiresAt.minusSeconds(300))
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void closesSessionWithExpiredToken() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("jwt", jwt("alice", Instant.now().minusSeconds(60)));
        WebSocketSession session = mockSession("s1", attributes);
        registry.add(session, "alice");

        sweeper.closeExpiredSessions();

        verify(session).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    @Test
    void keepsSessionWithValidToken() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("jwt", jwt("alice", Instant.now().plusSeconds(300)));
        WebSocketSession session = mockSession("s1", attributes);
        registry.add(session, "alice");

        sweeper.closeExpiredSessions();

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void closesSessionWhoseTokenHasNoExpiry() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("jwt", Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("alice")
                .build());
        WebSocketSession session = mockSession("s1", attributes);
        registry.add(session, "alice");

        sweeper.closeExpiredSessions();

        verify(session).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    @Test
    void closesSessionWithoutJwtAttribute() throws Exception {
        WebSocketSession session = mockSession("s1", new HashMap<>());
        registry.add(session, "alice");

        sweeper.closeExpiredSessions();

        verify(session).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    @Test
    void refreshedTokenInAttributesPreventsClosure() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("jwt", jwt("alice", Instant.now().minusSeconds(60)));
        WebSocketSession session = mockSession("s1", attributes);
        registry.add(session, "alice");

        // Simulates the refresh action replacing the stored JWT before the sweep runs.
        attributes.put("jwt", jwt("alice", Instant.now().plusSeconds(300)));
        sweeper.closeExpiredSessions();

        verify(session, never()).close(any(CloseStatus.class));
    }
}
