package org.munycha.logstream.config;

import org.munycha.logstream.websocket.LogWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

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
                .addInterceptors(new TokenHandshakeInterceptor(properties))
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

    private static class TokenHandshakeInterceptor implements HandshakeInterceptor {

        private final LogstreamProperties properties;

        private TokenHandshakeInterceptor(LogstreamProperties properties) {
            this.properties = properties;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request,
                                       ServerHttpResponse response,
                                       org.springframework.web.socket.WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
            if (!properties.isAuthEnabled()) {
                return true;
            }

            String token = AuthTokenSupport.extract(request);
            if (properties.matchesAuthToken(token)) {
                return true;
            }

            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   org.springframework.web.socket.WebSocketHandler wsHandler,
                                   Exception exception) {
            // No handshake state to clean up.
        }
    }
}
