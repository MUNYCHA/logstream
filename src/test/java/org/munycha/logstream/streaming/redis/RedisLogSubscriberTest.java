package org.munycha.logstream.streaming.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.munycha.logstream.streaming.broadcast.LogBroadcastService;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisLogSubscriberTest {

    private LogBroadcastService broadcastService;
    private RedisLogSubscriber subscriber;

    @BeforeEach
    void setUp() {
        broadcastService = mock(LogBroadcastService.class);
        Jackson2JsonRedisSerializer<LogEvent> serializer =
                new Jackson2JsonRedisSerializer<>(new ObjectMapper(), LogEvent.class);
        subscriber = new RedisLogSubscriber(broadcastService, serializer);
    }

    private Message message(String channel, String body) {
        return new DefaultMessage(channel.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deserializesValidJsonAndBroadcasts() {
        String json = "{\"serverName\":\"web-01\",\"path\":\"/var/log/app.log\",\"channel\":\"app1-channel\","
                + "\"timestamp\":\"2026-08-12T10:00:00Z\",\"message\":\"hello\"}";

        subscriber.onMessage(message("app1-channel", json), null);

        LogEvent expected = new LogEvent("web-01", "/var/log/app.log", "app1-channel", "2026-08-12T10:00:00Z", "hello");
        verify(broadcastService).broadcast(expected);
    }

    @Test
    void dropsMalformedBodyWithoutThrowingOrBroadcasting() {
        subscriber.onMessage(message("app1-channel", "not json"), null);

        verify(broadcastService, never()).broadcast(any());
    }
}
