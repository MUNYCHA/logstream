package org.munycha.logstream.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.munycha.logstream.model.LogEvent;
import org.munycha.logstream.service.LogBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public void consume(List<ConsumerRecord<String, LogEvent>> records) {
        log.debug("Received batch of {} log records", records.size());
        for (ConsumerRecord<String, LogEvent> record : records) {
            LogEvent event = record.value();
            if (event == null) {
                log.debug("Dropping null log event from topic {}", record.topic());
                continue;
            }
            broadcastService.broadcast(event.withTopic(record.topic()));
        }
    }
}
