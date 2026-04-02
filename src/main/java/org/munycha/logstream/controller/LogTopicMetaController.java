package org.munycha.logstream.controller;

import org.munycha.logstream.config.LogstreamProperties;
import org.munycha.logstream.service.TopicMetaStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/topics")
public class LogTopicMetaController {

    private final TopicMetaStore metaStore;
    private final LogstreamProperties properties;

    public LogTopicMetaController(TopicMetaStore metaStore, LogstreamProperties properties) {
        this.metaStore = metaStore;
        this.properties = properties;
    }

    @GetMapping("/{topic}/meta")
    public ResponseEntity<TopicMetaStore.TopicMetaResponse> getMeta(@PathVariable String topic) {
        if (topic.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topic is required");
        }
        if (properties.getTopics() == null || !properties.getTopics().contains(topic)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown topic");
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS))
                .body(metaStore.getMeta(topic));
    }
}
