package org.munycha.logstream.streaming.redis;

import org.munycha.logstream.streaming.broadcast.LogBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.stereotype.Component;

@Component
public class RedisLogSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisLogSubscriber.class);

    private final LogBroadcastService broadcastService;
    private final Jackson2JsonRedisSerializer<LogEvent> serializer;

    public RedisLogSubscriber(LogBroadcastService broadcastService, Jackson2JsonRedisSerializer<LogEvent> serializer) {
        this.broadcastService = broadcastService;
        this.serializer = serializer;
    }

    /**
     * Called from the Redis subscriber thread — deserializes and enqueues the event
     * for batched broadcast. Non-blocking so it never stalls the subscriber thread.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        LogEvent event;
        try {
            event = serializer.deserialize(message.getBody());
        } catch (Exception e) {
            log.debug("Dropping unparseable Redis message on channel {}: {}", message.getChannel(), e.getMessage());
            return;
        }
        broadcastService.broadcast(event);
    }
}
