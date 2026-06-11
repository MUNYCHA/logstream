package org.munycha.logstream.streaming.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketSessionRegistryTest {

    private LogstreamProperties properties;
    private WebSocketSessionRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new LogstreamProperties();
        properties.setMaxSessionsPerUser(2);
        registry = new WebSocketSessionRegistry(properties);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    @Test
    void rejectsSessionsBeyondPerUserCap() {
        assertNotNull(registry.add(mockSession("s1"), "alice"));
        assertNotNull(registry.add(mockSession("s2"), "alice"));
        assertNull(registry.add(mockSession("s3"), "alice"));
    }

    @Test
    void capIsPerUserNotGlobal() {
        registry.add(mockSession("s1"), "alice");
        registry.add(mockSession("s2"), "alice");
        assertNotNull(registry.add(mockSession("s3"), "bob"));
    }

    @Test
    void removeFreesTheSlot() {
        WebSocketSession first = mockSession("s1");
        registry.add(first, "alice");
        registry.add(mockSession("s2"), "alice");
        registry.remove(first);
        assertNotNull(registry.add(mockSession("s3"), "alice"));
    }

    @Test
    void removingNeverRegisteredSessionIsSafe() {
        registry.remove(mockSession("ghost"));
        assertNotNull(registry.add(mockSession("s1"), "alice"));
    }

    @Test
    void nullSubjectIsNotCapped() {
        assertNotNull(registry.add(mockSession("s1"), null));
        assertNotNull(registry.add(mockSession("s2"), null));
        assertNotNull(registry.add(mockSession("s3"), null));
    }

    @Test
    void nonPositiveCapDisablesTheLimit() {
        properties.setMaxSessionsPerUser(0);
        registry.add(mockSession("s1"), "alice");
        registry.add(mockSession("s2"), "alice");
        assertNotNull(registry.add(mockSession("s3"), "alice"));
    }
}
