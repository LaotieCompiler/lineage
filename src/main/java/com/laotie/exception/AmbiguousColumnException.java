package com.laotie.exception;

import java.util.List;

/**
 * Raised when an unqualified column exists in more than one source table of a query block and
 * the metadata cannot decide which one the query refers to.
 */
public class AmbiguousColumnException extends RuntimeException {

    public AmbiguousColumnException(String columnName, List<String> tables) {
        super(String.format("ambiguous column '%s' found in tables: %s",
                columnName, String.join(", ", tables)));
    }
}
