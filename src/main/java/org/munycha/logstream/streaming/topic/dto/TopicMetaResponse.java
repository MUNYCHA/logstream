package org.munycha.logstream.streaming.topic.dto;

import java.util.List;

/** REST response for {@code GET /api/topics/{topic}/meta}. */
public record TopicMetaResponse(List<ServerEntry> servers) {

    public record ServerEntry(String name, long count, String lastSeen, List<PathEntry> paths) {}

    public record PathEntry(String path, long count, String lastSeen) {}
}
