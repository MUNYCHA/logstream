package org.munycha.logstream.streaming.broadcast;

import org.munycha.logstream.streaming.websocket.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Sends messages to sessions and evicts those whose connection has failed.
 * Slow-client protection lives in the {@code ConcurrentWebSocketSessionDecorator}
 * the registry wraps every session in: sends are buffered per session (so the
 * calling scheduler never blocks on a slow client), and the decorator closes any
 * session that exceeds its send-time or buffer limit.
 */
@Component
public class SessionBackpressure {

    private static final Logger log = LoggerFactory.getLogger(SessionBackpressure.class);

    private final WebSocketSessionRegistry sessionRegistry;

    public SessionBackpressure(WebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /** Send a message to a session; removes the session if the send fails. */
    public void send(WebSocketSession session, TextMessage message) {
        try {
            session.sendMessage(message);
        } catch (Exception e) {
            log.warn("Failed to send to session {}, removing it", session.getId(), e);
            sessionRegistry.remove(session);
        }
    }
}
