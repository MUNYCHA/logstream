package org.munycha.logstream.streaming.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.time.Instant;

/**
 * Closes sessions whose JWT has expired, so a stream never outlives its authorization.
 * The handshake validates the token once; without this sweep a connection would keep
 * streaming for hours after the user's token lapsed or their account was disabled.
 *
 * <p>Active clients are expected to stay ahead of the sweep by pushing renewed tokens
 * via the {@code refresh} action (see {@code LogWebSocketHandler#handleRefresh}), which
 * replaces the {@code jwt} handshake attribute this sweep reads. Closure uses
 * {@code 1008 Token expired}; the UI reconnects with a freshly renewed token.
 */
@Component
public class SessionExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(SessionExpirySweeper.class);

    private final WebSocketSessionRegistry sessionRegistry;

    public SessionExpirySweeper(WebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Scheduled(fixedDelay = 60_000)
    public void closeExpiredSessions() {
        Instant now = Instant.now();
        sessionRegistry.forEach(session -> {
            // Fail closed: a session survives the sweep only with a stored token
            // whose expiry is provably in the future. No token or no exp claim
            // means authorization can't be verified — close it.
            Instant expiresAt = session.getAttributes().get("jwt") instanceof Jwt jwt
                    ? jwt.getExpiresAt() : null;
            if (expiresAt != null && expiresAt.isAfter(now)) return;
            log.info("Closing session {}: {}", session.getId(),
                    expiresAt == null ? "token has no expiry" : "token expired at " + expiresAt);
            try {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Token expired"));
            } catch (IOException e) {
                log.warn("Failed to close expired session {}: {}", session.getId(), e.getMessage());
                sessionRegistry.remove(session);
            }
        });
    }
}
