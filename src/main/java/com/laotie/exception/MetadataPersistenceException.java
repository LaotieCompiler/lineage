package com.laotie.exception;

/**
 * Raised when metadata DDL cannot be persisted, loaded, or represented as DDL text.
 */
public class MetadataPersistenceException extends RuntimeException {

    public MetadataPersistenceException(String message) {
        super(message);
    }

    public MetadataPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
