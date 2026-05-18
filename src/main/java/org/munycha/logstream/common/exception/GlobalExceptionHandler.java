package org.munycha.logstream.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LogFileNotFoundException.class)
    public ResponseEntity<ApiError> handleLogFileNotFound(LogFileNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "LOG_FILE_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTopicException.class)
    public ResponseEntity<ApiError> handleInvalidTopic(InvalidTopicException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_TOPIC", ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        ApiError body = new ApiError(status.value(), code, message, Instant.now(), request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
