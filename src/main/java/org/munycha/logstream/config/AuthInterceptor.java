package org.munycha.logstream.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final LogstreamProperties properties;

    public AuthInterceptor(LogstreamProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isAuthEnabled()) {
            return true;
        }

        String token = AuthTokenSupport.extract(request);
        if (properties.matchesAuthToken(token)) {
            return true;
        }

        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized");
        return false;
    }
}
