package com.laotie.exception;

import java.util.List;

/**
 * Raised when a table reference matches more than one table in the metadata catalog.
 */
public class AmbiguousTableException extends RuntimeException {

    public AmbiguousTableException(String reference, List<String> candidates) {
        super(String.format("ambiguous table '%s', candidates: %s",
                reference, String.join(", ", candidates)));
    }
}
