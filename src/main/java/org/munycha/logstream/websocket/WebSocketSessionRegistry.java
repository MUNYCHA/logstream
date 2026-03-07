package org.munycha.logstream.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class WebSocketSessionRegistry {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public void add(WebSocketSession session) {
        sessions.add(session);
    }

    public void remove(WebSocketSession session) {
        sessions.remove(session);
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
