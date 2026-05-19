package org.munycha.logstream.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final String BEARER_PREFIX = "bearer.";

    private final JwtDecoder jwtDecoder;

    public JwtHandshakeInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractBearerSubprotocol(request);
        if (token == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);
            attributes.put("jwt", jwt);
            attributes.put("subject", jwt.getSubject());
            return true;
        } catch (JwtException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    // Browser offers ['logstream.v1', 'bearer.<jwt>'] in Sec-WebSocket-Protocol.
    // The header may arrive as one comma-joined value or as multiple values.
    private String extractBearerSubprotocol(ServerHttpRequest request) {
        List<String> headers = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(BEARER_PREFIX) && trimmed.length() > BEARER_PREFIX.length()) {
                    return trimmed.substring(BEARER_PREFIX.length());
                }
            }
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
