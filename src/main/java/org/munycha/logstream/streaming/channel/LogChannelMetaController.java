package org.munycha.logstream.streaming.channel;

import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.common.exception.InvalidChannelException;
import org.munycha.logstream.streaming.channel.dto.ChannelMetaResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/channels")
public class LogChannelMetaController {

    private final ChannelMetaStore metaStore;
    private final LogstreamProperties properties;

    public LogChannelMetaController(ChannelMetaStore metaStore, LogstreamProperties properties) {
        this.metaStore = metaStore;
        this.properties = properties;
    }

    @GetMapping("/{channel}/meta")
    public ResponseEntity<ChannelMetaResponse> getMeta(@PathVariable String channel) {
        if (channel.isBlank()) {
            throw new InvalidChannelException("Channel is required");
        }
        if (properties.getChannels() == null || !properties.getChannels().contains(channel)) {
            throw new InvalidChannelException("Unknown channel");
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS))
                .body(metaStore.getMeta(channel));
    }
}
