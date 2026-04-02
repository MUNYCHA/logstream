package org.munycha.logstream.service;

import org.munycha.logstream.model.LogEvent;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory accumulator: topic → server → path → { count, lastSeen }.
 * Written by the flush thread; read by HTTP threads — ConcurrentHashMap + atomics
 * ensure no locking on reads.
 */
@Service
public class TopicMetaStore {

    // topic → serverName → ServerMeta
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ServerMeta>> store =
            new ConcurrentHashMap<>();

    /** Called from LogBroadcastService flush thread for every drained event. */
    public void record(LogEvent event) {
        ServerMeta server = store
                .computeIfAbsent(event.topic(), t -> new ConcurrentHashMap<>())
                .computeIfAbsent(event.serverName(), s -> new ServerMeta());
        server.count.increment();
        server.lastSeen.set(event.timestamp());

        PathMeta path = server.paths.computeIfAbsent(event.path(), p -> new PathMeta());
        path.count.increment();
        path.lastSeen.set(event.timestamp());
    }

    /** Returns a snapshot sorted by count desc. Returns empty servers list if topic unknown. */
    public TopicMetaResponse getMeta(String topic) {
        ConcurrentHashMap<String, ServerMeta> servers = store.get(topic);
        if (servers == null) return new TopicMetaResponse(List.of());

        List<ServerEntry> result = servers.entrySet().stream()
                .map(e -> {
                    ServerMeta sm = e.getValue();
                    List<PathEntry> paths = sm.paths.entrySet().stream()
                            .map(pe -> new PathEntry(
                                    pe.getKey(),
                                    pe.getValue().count.sum(),
                                    pe.getValue().lastSeen.get()))
                            .sorted(Comparator.comparingLong(PathEntry::count).reversed())
                            .toList();
                    return new ServerEntry(e.getKey(), sm.count.sum(), sm.lastSeen.get(), paths);
                })
                .sorted(Comparator.comparingLong(ServerEntry::count).reversed())
                .toList();

        return new TopicMetaResponse(result);
    }

    // --- Response shapes ---

    public record TopicMetaResponse(List<ServerEntry> servers) {}

    public record ServerEntry(String name, long count, String lastSeen, List<PathEntry> paths) {}

    public record PathEntry(String path, long count, String lastSeen) {}

    // --- Internal mutable state ---

    private static class ServerMeta {
        final LongAdder count = new LongAdder();
        final AtomicReference<String> lastSeen = new AtomicReference<>("");
        final ConcurrentHashMap<String, PathMeta> paths = new ConcurrentHashMap<>();
    }

    private static class PathMeta {
        final LongAdder count = new LongAdder();
        final AtomicReference<String> lastSeen = new AtomicReference<>("");
    }
}
