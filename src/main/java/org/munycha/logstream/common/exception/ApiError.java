package org.munycha.logstream.common.exception;

import java.time.Instant;

/**
 * Standard error response body for the REST API.
 * Intentionally omits exception class names and stack details — keeps surface minimal.
 */
public record ApiError(int status, String code, String message, Instant timestamp, String path) {}
