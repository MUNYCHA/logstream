package org.munycha.logstream.streaming.websocket.dto;

import java.util.Set;

/** Per-topic stats payload — event rate and active server set for one interval. */
public record TopicStat(long rate, Set<String> servers) {}
