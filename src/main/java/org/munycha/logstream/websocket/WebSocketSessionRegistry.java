package org.munycha.logstream.websocket;

import org.munycha.logstream.model.ClientFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class WebSocketSessionRegistry {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientFilter> filters = new ConcurrentHashMap<>();
    private final List<Consumer<String>> removeListeners = new CopyOnWriteArrayList<>();

    /** Register a callback invoked when a session is removed (for cleanup). */
    public void onRemove(Consumer<String> listener) {
        removeListeners.add(listener);
    }

    public void add(WebSocketSession session) {
        sessions.add(session);
    }

    public void remove(WebSocketSession session) {
        sessions.remove(session);
        String id = session.getId();
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

    public void forEach(Consumer<WebSocketSession> action) {
        sessions.stream()
                .filter(WebSocketSession::isOpen)
                .forEach(action);
    }

    public int activeCount() {
        return (int) sessions.stream().filter(WebSocketSession::isOpen).count();
    }
}
