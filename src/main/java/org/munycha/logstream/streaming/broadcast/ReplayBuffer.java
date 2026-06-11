package org.munycha.logstream.streaming.broadcast;

import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.streaming.kafka.LogEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the last N events per topic so a freshly subscribed session sees recent
 * history instead of a blank panel. Memory is bounded: capacity × topics events,
 * oldest overwritten first. The flush thread writes; Tomcat WS threads read on
 * subscribe — both rare and cheap enough that a per-topic synchronized ring is fine.
 */
@Component
public class ReplayBuffer {

    private final int capacity;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, TopicRing> rings = new ConcurrentHashMap<>();

    public ReplayBuffer(LogstreamProperties properties) {
        this.capacity = properties.getReplayBufferSize();
    }

    public void record(LogEvent event) {
        if (capacity <= 0) return;
        rings.computeIfAbsent(event.topic(), k -> new TopicRing(capacity))
                .add(new Entry(sequence.incrementAndGet(), event));
    }

    /** Buffered events for the given topics, merged in arrival order (oldest first). */
    public List<LogEvent> replayFor(Set<String> topics) {
        if (capacity <= 0 || topics.isEmpty()) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String topic : topics) {
            TopicRing ring = rings.get(topic);
            if (ring != null) {
                entries.addAll(ring.snapshot());
            }
        }
        entries.sort(Comparator.comparingLong(Entry::seq));
        return entries.stream().map(Entry::event).toList();
    }

    /** Global arrival sequence so multi-topic replays interleave in true order. */
    private record Entry(long seq, LogEvent event) {}

    private static final class TopicRing {
        private final Entry[] buffer;
        private int next;
        private int size;

        TopicRing(int capacity) {
            this.buffer = new Entry[capacity];
        }

        synchronized void add(Entry entry) {
            buffer[next] = entry;
            next = (next + 1) % buffer.length;
            if (size < buffer.length) size++;
        }

        synchronized List<Entry> snapshot() {
            List<Entry> out = new ArrayList<>(size);
            int start = (next - size + buffer.length) % buffer.length;
            for (int i = 0; i < size; i++) {
                out.add(buffer[(start + i) % buffer.length]);
            }
            return out;
        }
    }
}
