package org.munycha.logstream.streaming.topic;

import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.common.exception.InvalidTopicException;
import org.munycha.logstream.streaming.topic.dto.TopicMetaResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<TopicMetaResponse> getMeta(@PathVariable String topic) {
        if (topic.isBlank()) {
            throw new InvalidTopicException("Topic is required");
        }
        if (properties.getTopics() == null || !properties.getTopics().contains(topic)) {
            throw new InvalidTopicException("Unknown topic");
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS))
                .body(metaStore.getMeta(topic));
    }
}
