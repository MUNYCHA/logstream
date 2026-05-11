package org.munycha.logstream.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.munycha.logstream.model.LogEvent;
import org.munycha.logstream.service.LogBroadcastService;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KafkaLogConsumerTest {

    @Mock
    LogBroadcastService broadcastService;

    @Test
    void consume_usesKafkaRecordTopicInsteadOfPayloadTopic() {
        KafkaLogConsumer consumer = new KafkaLogConsumer(broadcastService);
        LogEvent event = new LogEvent(
                "web-01",
                "/var/log/app.log",
                "payload-topic",
                Instant.now().toString(),
                "hello"
        );

        consumer.consume(List.of(new ConsumerRecord<>("actual-topic", 0, 42L, "key", event)));

        ArgumentCaptor<LogEvent> captor = ArgumentCaptor.forClass(LogEvent.class);
        verify(broadcastService).broadcast(captor.capture());
        assertEquals("actual-topic", captor.getValue().topic());
    }

    @Test
    void consume_dropsNullValuesFromDeserializerFailures() {
        KafkaLogConsumer consumer = new KafkaLogConsumer(broadcastService);

        consumer.consume(List.of(new ConsumerRecord<>("actual-topic", 0, 42L, "key", null)));

        verifyNoInteractions(broadcastService);
    }
}
