package org.munycha.logstream.common.exception;

/** Thrown for malformed or unknown channel arguments. Maps to 400. */
public class InvalidChannelException extends RuntimeException {
    public InvalidChannelException(String message) {
        super(message);
    }

    public InvalidChannelException() {
        this("Invalid channel");
    }
}
