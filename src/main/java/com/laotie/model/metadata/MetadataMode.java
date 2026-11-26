package com.laotie.model.metadata;

/**
 * How the lineage analyzer treats metadata that is missing or incomplete.
 */
public enum MetadataMode {

    /**
     * Metadata is authoritative. A table that is absent from the catalog is an error, and a
     * column that cannot be matched unambiguously is an error.
     */
    STRICT,

    /**
     * Metadata is optional. A table that is absent from the catalog is still analyzed: its
     * wildcard stays a wildcard instead of being expanded into invented column names, and the
     * table-level edge is kept in the global graph. A column that cannot be matched
     * unambiguously is an error.
     */
    OPTIONAL
}
