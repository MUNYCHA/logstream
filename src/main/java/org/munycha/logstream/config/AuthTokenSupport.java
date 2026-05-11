package org.munycha.logstream.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.util.UriComponentsBuilder;

final class AuthTokenSupport {

    private static final String TOKEN_HEADER = "X-Logstream-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private AuthTokenSupport() {
    }

    static String extract(HttpServletRequest request) {
        String token = firstText(request.getHeader(TOKEN_HEADER));
        if (token != null) {
            return token;
        }

        token = extractBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token != null) {
            return token;
        }

        return firstText(request.getParameter("token"));
    }

    static String extract(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String token = firstText(headers.getFirst(TOKEN_HEADER));
        if (token != null) {
            return token;
        }

        token = extractBearer(headers.getFirst(HttpHeaders.AUTHORIZATION));
        if (token != null) {
            return token;
        }

        return firstText(UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token"));
    }

    private static String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return firstText(authorization.substring(BEARER_PREFIX.length()));
    }

    private static String firstText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
