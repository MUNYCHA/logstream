package org.munycha.logstream.streaming.channel;

import org.munycha.logstream.streaming.redis.LogEvent;
import org.munycha.logstream.streaming.channel.dto.ChannelMetaResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory accumulator: channel → server → path → { count, lastSeen }.
 * Written by the flush thread; read by HTTP threads — ConcurrentHashMap + atomics
 * ensure no locking on reads.
 */
@Service
public class ChannelMetaStore {

    // channel → serverName → ServerMeta
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ServerMeta>> store =
            new ConcurrentHashMap<>();

    /** Called from LogBroadcastService flush thread for every drained event. */
    public void record(LogEvent event) {
        ServerMeta server = store
                .computeIfAbsent(event.channel(), c -> new ConcurrentHashMap<>())
                .computeIfAbsent(event.serverName(), s -> new ServerMeta());
        server.count.increment();
        server.lastSeen.set(event.timestamp());

        PathMeta path = server.paths.computeIfAbsent(event.path(), p -> new PathMeta());
        path.count.increment();
        path.lastSeen.set(event.timestamp());
    }

    /** Returns a snapshot sorted by count desc. Returns empty servers list if channel unknown. */
    public ChannelMetaResponse getMeta(String channel) {
        ConcurrentHashMap<String, ServerMeta> servers = store.get(channel);
        if (servers == null) return new ChannelMetaResponse(List.of());

        List<ChannelMetaResponse.ServerEntry> result = servers.entrySet().stream()
                .map(e -> {
                    ServerMeta sm = e.getValue();
                    List<ChannelMetaResponse.PathEntry> paths = sm.paths.entrySet().stream()
                            .map(pe -> new ChannelMetaResponse.PathEntry(
                                    pe.getKey(),
                                    pe.getValue().count.sum(),
                                    pe.getValue().lastSeen.get()))
                            .sorted(Comparator.comparingLong(ChannelMetaResponse.PathEntry::count).reversed())
                            .toList();
                    return new ChannelMetaResponse.ServerEntry(e.getKey(), sm.count.sum(), sm.lastSeen.get(), paths);
                })
                .sorted(Comparator.comparingLong(ChannelMetaResponse.ServerEntry::count).reversed())
                .toList();

        return new ChannelMetaResponse(result);
    }

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
