package org.munycha.logstream.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtHandshakeInterceptorTest {

    private JwtDecoder jwtDecoder;
    private JwtHandshakeInterceptor interceptor;
    private ServerHttpResponse response;
    private WebSocketHandler wsHandler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        interceptor = new JwtHandshakeInterceptor(jwtDecoder);
        response = mock(ServerHttpResponse.class);
        wsHandler = mock(WebSocketHandler.class);
        attributes = new HashMap<>();
    }

    private ServerHttpRequest requestWithProtocols(String... protocolHeaders) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        for (String header : protocolHeaders) {
            headers.add("Sec-WebSocket-Protocol", header);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private Jwt jwt(String subject) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        return (subject != null ? builder.subject(subject) : builder).build();
    }

    @Test
    void validTokenWithSubjectIsAcceptedAndStashed() {
        Jwt token = jwt("alice");
        when(jwtDecoder.decode("good")).thenReturn(token);
        ServerHttpRequest request = requestWithProtocols("logstream.v1, bearer.good");

        assertTrue(interceptor.beforeHandshake(request, response, wsHandler, attributes));
        assertEquals(token, attributes.get("jwt"));
        assertEquals("alice", attributes.get("subject"));
    }

    @Test
    void tokenWithoutSubjectIsRejected() {
        when(jwtDecoder.decode("anonymous")).thenReturn(jwt(null));
        ServerHttpRequest request = requestWithProtocols("logstream.v1, bearer.anonymous");

        assertFalse(interceptor.beforeHandshake(request, response, wsHandler, attributes));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertTrue(attributes.isEmpty());
    }

    @Test
    void invalidTokenIsRejected() {
        when(jwtDecoder.decode("bad")).thenThrow(new JwtException("invalid signature"));
        ServerHttpRequest request = requestWithProtocols("logstream.v1, bearer.bad");

        assertFalse(interceptor.beforeHandshake(request, response, wsHandler, attributes));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingBearerSubprotocolIsRejected() {
        ServerHttpRequest request = requestWithProtocols("logstream.v1");

        assertFalse(interceptor.beforeHandshake(request, response, wsHandler, attributes));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
