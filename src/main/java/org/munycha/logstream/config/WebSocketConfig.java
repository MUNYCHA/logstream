package org.munycha.logstream.config;

import org.munycha.logstream.websocket.LogWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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
}
