package org.munycha.logstream.streaming.broadcast;

import org.munycha.logstream.streaming.kafka.LogEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-topic counters + active-server tracking for periodic stats broadcasting.
 * The flush thread calls {@link #record}; the stats broadcaster calls {@link #drain}
 * to atomically swap the accumulators for the next interval.
 */
@Component
public class StatsAccumulator {

    private final AtomicReference<ConcurrentHashMap<String, LongAdder>> counters =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, Set<String>>> servers =
            new AtomicReference<>(new ConcurrentHashMap<>());

    public void record(LogEvent event) {
        counters.get()
                .computeIfAbsent(event.topic(), k -> new LongAdder())
                .increment();
        servers.get()
                .computeIfAbsent(event.topic(), k -> ConcurrentHashMap.newKeySet())
                .add(event.serverName());
    }

    /** Atomically swaps and returns the accumulated snapshot; resets for the next interval. */
    public Snapshot drain() {
        return new Snapshot(
                counters.getAndSet(new ConcurrentHashMap<>()),
                servers.getAndSet(new ConcurrentHashMap<>())
        );
    }

    public record Snapshot(Map<String, LongAdder> counts, Map<String, Set<String>> servers) {
        public boolean isEmpty() {
            return counts.isEmpty() && servers.isEmpty();
        }
    }
}
