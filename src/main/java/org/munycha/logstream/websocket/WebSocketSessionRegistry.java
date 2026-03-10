package org.munycha.logstream.websocket;

import org.munycha.logstream.model.ClientFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class WebSocketSessionRegistry {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientFilter> filters = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.add(session);
    }

    public void remove(WebSocketSession session) {
        sessions.remove(session);
        subscriptions.remove(session.getId());
        filters.remove(session.getId());
    }

    public void subscribe(WebSocketSession session, Set<String> topics) {
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
        subscriptions.get(session.getId()).addAll(topics);
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
