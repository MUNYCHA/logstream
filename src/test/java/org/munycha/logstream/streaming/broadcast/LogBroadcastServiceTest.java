package org.munycha.logstream.streaming.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.filter.ClientFilter;
import org.munycha.logstream.streaming.filter.LogFilterEngine;
import org.munycha.logstream.streaming.kafka.LogEvent;
import org.munycha.logstream.streaming.topic.TopicMetaStore;
import org.munycha.logstream.streaming.websocket.WebSocketSessionRegistry;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogBroadcastServiceTest {

    private WebSocketSessionRegistry registry;
    private SessionBackpressure backpressure;
    private LogBroadcastService service;

    @BeforeEach
    void setUp() {
        registry = new WebSocketSessionRegistry(new LogstreamProperties());
        backpressure = mock(SessionBackpressure.class);
        service = new LogBroadcastService(
                registry,
                new ObjectMapper(),
                new LogFilterEngine(),
                mock(TopicMetaStore.class),
                new StatsAccumulator(),
                backpressure);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private LogEvent event(String topic, String server, String message) {
        return new LogEvent(server, "/var/log/app.log", topic, "2026-06-11T08:00:00Z", message);
    }

    /** Captures every (session, message) pair sent during flush, keyed by session id. */
    private Map<String, List<TextMessage>> capturedSends() {
        ArgumentCaptor<WebSocketSession> sessionCaptor = ArgumentCaptor.forClass(WebSocketSession.class);
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(backpressure, atLeastOnce()).send(sessionCaptor.capture(), messageCaptor.capture());
        Map<String, List<TextMessage>> bySession = new HashMap<>();
        List<WebSocketSession> sessions = sessionCaptor.getAllValues();
        List<TextMessage> messages = messageCaptor.getAllValues();
        for (int i = 0; i < sessions.size(); i++) {
            bySession.computeIfAbsent(sessions.get(i).getId(), k -> new ArrayList<>()).add(messages.get(i));
        }
        return bySession;
    }

    @Test
    void identicalSubscriptionsShareOneSerializedMessage() {
        WebSocketSession s1 = mockSession("s1");
        WebSocketSession s2 = mockSession("s2");
        registry.add(s1, "alice");
        registry.add(s2, "bob");
        registry.subscribe(s1, Set.of("t1"));
        registry.subscribe(s2, Set.of("t1"));

        service.broadcast(event("t1", "srv-a", "hello"));
        service.flush();

        Map<String, List<TextMessage>> sends = capturedSends();
        assertEquals(1, sends.get("s1").size());
        assertEquals(1, sends.get("s2").size());
        // Same instance, not just same content — serialization happened once for the group.
        assertSame(sends.get("s1").get(0), sends.get("s2").get(0));
    }

    @Test
    void differentFiltersGetIndependentlyFilteredPayloads() {
        WebSocketSession plain = mockSession("plain");
        WebSocketSession filtered = mockSession("filtered");
        registry.add(plain, "alice");
        registry.add(filtered, "bob");
        registry.subscribe(plain, Set.of("t1"));
        registry.subscribe(filtered, Set.of("t1"));
        registry.setFilter(filtered,
                new ClientFilter("srv-a", null, null, List.of(), "or", "all", 0));

        service.broadcast(event("t1", "srv-a", "from-a"));
        service.broadcast(event("t1", "srv-b", "from-b"));
        service.flush();

        Map<String, List<TextMessage>> sends = capturedSends();
        String plainPayload = sends.get("plain").get(0).getPayload();
        String filteredPayload = sends.get("filtered").get(0).getPayload();
        assertTrue(plainPayload.contains("from-a") && plainPayload.contains("from-b"));
        assertTrue(filteredPayload.contains("from-a"));
        assertFalse(filteredPayload.contains("from-b"));
    }

    @Test
    void unsubscribedSessionsReceiveNothing() {
        WebSocketSession idle = mockSession("idle");
        registry.add(idle, "alice");

        service.broadcast(event("t1", "srv-a", "hello"));
        service.flush();

        verify(backpressure, never()).send(any(), any());
    }

    @Test
    void subscriptionToOtherTopicsReceivesNothing() {
        WebSocketSession other = mockSession("other");
        registry.add(other, "alice");
        registry.subscribe(other, Set.of("t2"));

        service.broadcast(event("t1", "srv-a", "hello"));
        service.flush();

        verify(backpressure, never()).send(any(), any());
    }

    @Test
    void largeBatchIsChunkedSharedAcrossTheGroup() {
        WebSocketSession s1 = mockSession("s1");
        WebSocketSession s2 = mockSession("s2");
        registry.add(s1, "alice");
        registry.add(s2, "bob");
        registry.subscribe(s1, Set.of("t1"));
        registry.subscribe(s2, Set.of("t1"));

        for (int i = 0; i < 150; i++) {
            service.broadcast(event("t1", "srv-a", "msg-" + i));
        }
        service.flush();

        // 150 events at MAX_BATCH_SIZE=100 → 2 chunks per session, each instance shared.
        Map<String, List<TextMessage>> sends = capturedSends();
        assertEquals(2, sends.get("s1").size());
        assertEquals(2, sends.get("s2").size());
        List<TextMessage> first = new ArrayList<>(sends.get("s1"));
        List<TextMessage> second = new ArrayList<>(sends.get("s2"));
        assertSame(first.get(0), second.get(0));
        assertSame(first.get(1), second.get(1));
    }
}
