package com.laotie.exception;

import java.util.List;

/**
 * Raised when an unqualified column cannot be found in any source table of a query block.
 */
public class ColumnNotFoundException extends RuntimeException {

    public ColumnNotFoundException(String columnName, List<String> tables) {
        super(String.format("column '%s' not found in tables: %s",
                columnName, String.join(", ", tables)));
    }
}
