package org.munycha.logstream.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.munycha.logstream.streaming.redis.LogEvent;
import org.munycha.logstream.streaming.redis.RedisLogSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;

@Configuration
public class RedisConfig {

    private final LogstreamProperties logstreamProperties;

    public RedisConfig(LogstreamProperties logstreamProperties) {
        this.logstreamProperties = logstreamProperties;
    }

    @Bean
    public Jackson2JsonRedisSerializer<LogEvent> logEventSerializer(ObjectMapper objectMapper) {
        return new Jackson2JsonRedisSerializer<>(objectMapper, LogEvent.class);
    }

    /**
     * One Redis channel per {@code logstream.channels} entry — mirrors the previous
     * one-Kafka-topic-per-channel model. Subscription happens asynchronously with built-in
     * backoff/recovery, so a broker that's unreachable at startup doesn't block application
     * context refresh — it just retries in the background.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisLogSubscriber redisLogSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        for (String channel : logstreamProperties.getChannels()) {
            container.addMessageListener(redisLogSubscriber, new ChannelTopic(channel));
        }
        return container;
    }
}
