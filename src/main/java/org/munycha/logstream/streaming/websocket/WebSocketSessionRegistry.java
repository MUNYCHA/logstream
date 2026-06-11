package org.munycha.logstream.streaming.websocket;

import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.filter.ClientFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class WebSocketSessionRegistry {

    /** Max time a single send may block before the session is marked unreliable and closed. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** Max bytes buffered for a slow session before it is closed. */
    private static final int SEND_BUFFER_SIZE_LIMIT = 2 * 1024 * 1024;

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientFilter> filters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> subjectSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionSubjects = new ConcurrentHashMap<>();

    private final LogstreamProperties properties;

    public WebSocketSessionRegistry(LogstreamProperties properties) {
        this.properties = properties;
    }

    /**
     * Registers a session, wrapping it in {@link ConcurrentWebSocketSessionDecorator} so sends
     * are thread-safe and buffered — a slow client only stalls (and eventually closes) its own
     * session instead of blocking the broadcast threads. Returns the wrapped session; all sends
     * must go through it, never the raw session.
     *
     * <p>Returns {@code null} when the subject is already at the per-user session limit;
     * the caller must close the connection and must not use the raw session.
     */
    public WebSocketSession add(WebSocketSession session, String subject) {
        if (!reserveSlot(session.getId(), subject)) {
            return null;
        }
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), decorated);
        return decorated;
    }

    /**
     * Atomically claims a per-subject slot — the size check and insert happen inside a single
     * {@code compute} so concurrent handshakes for the same subject cannot both pass the cap.
     */
    private boolean reserveSlot(String sessionId, String subject) {
        int cap = properties.getMaxSessionsPerUser();
        if (cap <= 0 || subject == null) return true;
        boolean[] accepted = new boolean[1];
        subjectSessions.compute(subject, (key, ids) -> {
            if (ids == null) ids = ConcurrentHashMap.newKeySet();
            if (ids.size() < cap) {
                ids.add(sessionId);
                accepted[0] = true;
            }
            return ids;
        });
        if (accepted[0]) {
            sessionSubjects.put(sessionId, subject);
        }
        return accepted[0];
    }

    /** Removes a session by id — accepts either the raw or the decorated instance. */
    public void remove(WebSocketSession session) {
        String id = session.getId();
        sessions.remove(id);
        subscriptions.remove(id);
        filters.remove(id);
        String subject = sessionSubjects.remove(id);
        if (subject != null) {
            subjectSessions.computeIfPresent(subject, (key, ids) -> {
                ids.remove(id);
                return ids.isEmpty() ? null : ids;
            });
        }
    }

    public void subscribe(WebSocketSession session, Set<String> topics) {
        Set<String> topicSet = ConcurrentHashMap.newKeySet();
        topicSet.addAll(topics);
        subscriptions.put(session.getId(), topicSet);
    }

    public boolean isSubscribed(WebSocketSession session, String topic) {
        Set<String> topics = subscriptions.get(session.getId());
        return topics != null && topics.contains(topic);
    }

    public boolean hasSubscriptions(WebSocketSession session) {
        return subscriptions.containsKey(session.getId());
    }

    public void setFilter(WebSocketSession session, ClientFilter filter) {
        if (filter == null || filter.isEmpty()) {
            filters.remove(session.getId());
        } else {
            filters.put(session.getId(), filter);
        }
    }

    public ClientFilter getFilter(WebSocketSession session) {
        return filters.getOrDefault(session.getId(), ClientFilter.EMPTY);
    }

    public void forEach(Consumer<WebSocketSession> action) {
        sessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .forEach(action);
    }

    public int activeCount() {
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }
}
