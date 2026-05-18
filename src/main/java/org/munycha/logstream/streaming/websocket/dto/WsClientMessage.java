package org.munycha.logstream.streaming.websocket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Sealed contract of all inbound WebSocket messages. Jackson dispatches on the
 * {@code action} discriminator to the correct subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WsClientMessage.Subscribe.class,    name = "subscribe"),
        @JsonSubTypes.Type(value = WsClientMessage.Filter.class,       name = "filter"),
        @JsonSubTypes.Type(value = WsClientMessage.ClearFilters.class, name = "clear-filters")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface WsClientMessage
        permits WsClientMessage.Subscribe, WsClientMessage.Filter, WsClientMessage.ClearFilters {

    record Subscribe(List<String> topics) implements WsClientMessage {}

    record Filter(ClientFilterRequest filters) implements WsClientMessage {}

    record ClearFilters() implements WsClientMessage {}
}
