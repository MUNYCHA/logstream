package org.munycha.logstream.common.exception;

/** Thrown for malformed or unknown topic arguments. Maps to 400. */
public class InvalidTopicException extends RuntimeException {
    public InvalidTopicException(String message) {
        super(message);
    }

    public InvalidTopicException() {
        this("Invalid topic");
    }
}
