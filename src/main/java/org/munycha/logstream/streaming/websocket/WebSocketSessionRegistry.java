package org.munycha.logstream.streaming.websocket;

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

    /**
     * Registers a session, wrapping it in {@link ConcurrentWebSocketSessionDecorator} so sends
     * are thread-safe and buffered — a slow client only stalls (and eventually closes) its own
     * session instead of blocking the broadcast threads. Returns the wrapped session; all sends
     * must go through it, never the raw session.
     */
    public WebSocketSession add(WebSocketSession session) {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), decorated);
        return decorated;
    }

    /** Removes a session by id — accepts either the raw or the decorated instance. */
    public void remove(WebSocketSession session) {
        String id = session.getId();
        sessions.remove(id);
        subscriptions.remove(id);
        filters.remove(id);
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
