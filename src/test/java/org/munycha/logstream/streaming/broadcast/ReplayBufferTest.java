package org.munycha.logstream.streaming.broadcast;

import org.junit.jupiter.api.Test;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.redis.LogEvent;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayBufferTest {

    private ReplayBuffer buffer(int capacity) {
        LogstreamProperties properties = new LogstreamProperties();
        properties.setReplayBufferSize(capacity);
        return new ReplayBuffer(properties);
    }

    private LogEvent event(String topic, String message) {
        return new LogEvent("srv-a", "/var/log/app.log", topic, "2026-06-11T08:00:00Z", message);
    }

    @Test
    void replaysInArrivalOrderOldestFirst() {
        ReplayBuffer buffer = buffer(10);
        buffer.record(event("t1", "first"));
        buffer.record(event("t1", "second"));
        buffer.record(event("t1", "third"));

        List<LogEvent> replay = buffer.replayFor(Set.of("t1"));
        assertEquals(List.of("first", "second", "third"),
                replay.stream().map(LogEvent::message).toList());
    }

    @Test
    void overflowKeepsOnlyTheNewestEvents() {
        ReplayBuffer buffer = buffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.record(event("t1", "msg-" + i));
        }

        List<LogEvent> replay = buffer.replayFor(Set.of("t1"));
        assertEquals(List.of("msg-3", "msg-4", "msg-5"),
                replay.stream().map(LogEvent::message).toList());
    }

    @Test
    void multipleTopicsMergeInTrueArrivalOrder() {
        ReplayBuffer buffer = buffer(10);
        buffer.record(event("t1", "a1"));
        buffer.record(event("t2", "b1"));
        buffer.record(event("t1", "a2"));

        List<LogEvent> replay = buffer.replayFor(Set.of("t1", "t2"));
        assertEquals(List.of("a1", "b1", "a2"),
                replay.stream().map(LogEvent::message).toList());
    }

    @Test
    void replayIsScopedToRequestedTopics() {
        ReplayBuffer buffer = buffer(10);
        buffer.record(event("t1", "keep"));
        buffer.record(event("t2", "skip"));

        List<LogEvent> replay = buffer.replayFor(Set.of("t1"));
        assertEquals(List.of("keep"), replay.stream().map(LogEvent::message).toList());
    }

    @Test
    void unknownTopicReplaysNothing() {
        ReplayBuffer buffer = buffer(10);
        buffer.record(event("t1", "hello"));
        assertTrue(buffer.replayFor(Set.of("t9")).isEmpty());
    }

    @Test
    void nonPositiveCapacityDisablesReplay() {
        ReplayBuffer buffer = buffer(0);
        buffer.record(event("t1", "hello"));
        assertTrue(buffer.replayFor(Set.of("t1")).isEmpty());
    }
}
