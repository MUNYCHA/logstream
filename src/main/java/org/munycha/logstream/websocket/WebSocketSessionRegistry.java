package org.munycha.logstream.websocket;

import org.munycha.logstream.model.ClientFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class WebSocketSessionRegistry {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientFilter> filters = new ConcurrentHashMap<>();
    private final List<Consumer<String>> removeListeners = new CopyOnWriteArrayList<>();

    /** Register a callback invoked when a session is removed (for cleanup). */
    public void onRemove(Consumer<String> listener) {
        removeListeners.add(listener);
    }

    public WebSocketSession add(WebSocketSession session) {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MS,
                BUFFER_SIZE_LIMIT_BYTES
        );
        sessions.put(session.getId(), decorated);
        return decorated;
    }

    public void remove(WebSocketSession session) {
        String id = session.getId();
        sessions.remove(id);
        subscriptions.remove(id);
        filters.remove(id);
        removeListeners.forEach(l -> l.accept(id));
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

    public WebSocketSession resolve(WebSocketSession session) {
        return sessions.getOrDefault(session.getId(), session);
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
