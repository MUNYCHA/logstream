package org.munycha.logstream.streaming.channel.dto;

import java.util.List;

/** REST response for {@code GET /api/channels/{channel}/meta}. */
public record ChannelMetaResponse(List<ServerEntry> servers) {

    public record ServerEntry(String name, long count, String lastSeen, List<PathEntry> paths) {}

    public record PathEntry(String path, long count, String lastSeen) {}
}
