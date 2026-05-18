package org.munycha.logstream.streaming.broadcast;

import org.munycha.logstream.streaming.websocket.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-session in-flight send count and drops messages for slow clients.
 * Owns the {@code synchronized(session)} contract required by WebSocketSession.
 */
@Component
public class SessionBackpressure {

    private static final Logger log = LoggerFactory.getLogger(SessionBackpressure.class);

    /** Max queued messages per session before dropping — prevents slow-client backpressure. */
    private static final int MAX_PENDING_PER_SESSION = 500;

    private final WebSocketSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> sendQueue = new ConcurrentHashMap<>();

    public SessionBackpressure(WebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
        sessionRegistry.onRemove(this::onSessionRemoved);
    }

    /** Send a message to a session; drops silently if pending count exceeds the cap. */
    public void send(WebSocketSession session, TextMessage message) {
        AtomicInteger pending = sendQueue.computeIfAbsent(session.getId(), k -> new AtomicInteger(0));
        if (!reserveSendSlot(session, pending)) return;
        trySend(session, message, pending);
    }

    private boolean reserveSendSlot(WebSocketSession session, AtomicInteger pending) {
        int current;
        do {
            current = pending.get();
            if (current >= MAX_PENDING_PER_SESSION) {
                log.debug("Dropping message for slow session {} (pending: {})", session.getId(), current);
                return false;
            }
        } while (!pending.compareAndSet(current, current + 1));
        return true;
    }

    private void trySend(WebSocketSession session, TextMessage message, AtomicInteger pending) {
        synchronized (session) {
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.warn("Failed to send to session {}, removing it", session.getId(), e);
                sessionRegistry.remove(session);
                sendQueue.remove(session.getId());
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private void onSessionRemoved(String sessionId) {
        sendQueue.remove(sessionId);
    }
}
