package com.github.agentos.server.model;

/** Raised when an OpenAI-compatible model request or response cannot produce a plan. */
public final class ModelClientException extends RuntimeException {

    /** Creates an exception with a safe diagnostic message. */
    public ModelClientException(String message) {
        super(message);
    }

    /** Creates an exception with a safe diagnostic message and its underlying cause. */
    public ModelClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
