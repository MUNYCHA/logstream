package org.munycha.logstream.config;

import org.munycha.logstream.websocket.LogWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LogWebSocketHandler handler;
    private final LogstreamProperties properties;

    public WebSocketConfig(LogWebSocketHandler handler, LogstreamProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/logs")
                .setAllowedOrigins(properties.getAllowedOrigins().toArray(new String[0]));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);   // 512 KB — handles large stack traces
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        container.setMaxSessionIdleTimeout(300_000L);        // 5 min idle timeout
        container.setAsyncSendTimeout(5_000L);               // 5s send timeout — drop slow clients
        return container;
    }
}
