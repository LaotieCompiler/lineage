package com.laotie.exception;

/**
 * Raised when strict quoted-identifier syntax is enabled and a table or column
 * reference is written without identifier quotes.
 */
public class UnquotedIdentifierException extends RuntimeException {

    private final String identifier;

    public UnquotedIdentifierException(String identifier) {
        super(String.format("identifier '%s' must be quoted", identifier));
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }
}
