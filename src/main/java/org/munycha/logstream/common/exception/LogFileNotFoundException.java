package org.munycha.logstream.common.exception;

/**
 * Thrown when a log file cannot be served — used for both "topic not in allowlist"
 * and "file missing on disk." Both cases map to 404 to avoid leaking which is which.
 */
public class LogFileNotFoundException extends RuntimeException {
    public LogFileNotFoundException() {
        super("Log file not found");
    }
}
