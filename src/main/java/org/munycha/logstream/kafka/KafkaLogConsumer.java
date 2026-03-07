package org.munycha.logstream.kafka;

import org.munycha.logstream.model.LogEvent;
import org.munycha.logstream.service.LogBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaLogConsumer.class);

    private final LogBroadcastService broadcastService;

    public KafkaLogConsumer(LogBroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @KafkaListener(
            topics = "#{'${logstream.topics}'.split(',')}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(LogEvent event) {
        log.debug("Received log event on topic '{}' from '{}'", event.topic(), event.serverName());
        broadcastService.broadcast(event);
    }
}
