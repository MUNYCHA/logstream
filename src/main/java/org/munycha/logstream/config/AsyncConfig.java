package org.munycha.logstream.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Bounded thread pool for @Async broadcast tasks.
 * Replaces the default SimpleAsyncTaskExecutor which creates unbounded threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10_000);  // buffer up to 10k pending broadcasts
        executor.setThreadNamePrefix("broadcast-");
        executor.setRejectedExecutionHandler((r, e) -> {
            // Under extreme load, drop the oldest queued task silently
            // rather than blocking the Kafka consumer thread
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        });
        executor.initialize();
        return executor;
    }
}
