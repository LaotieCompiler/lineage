package com.laotie;

import com.laotie.model.metadata.MetadataMode;

/**
 * Options controlling how a lineage scan treats metadata.
 */
public final class LineageOptions {

    private static final LineageOptions DEFAULT =
            new LineageOptions(MetadataMode.STRICT, false, false);

    private final MetadataMode metadataMode;
    private final boolean inferMetadata;
    private final boolean strictQuotedIdentifiers;

    private LineageOptions(MetadataMode metadataMode, boolean inferMetadata,
            boolean strictQuotedIdentifiers) {
        this.metadataMode = metadataMode == null ? MetadataMode.STRICT : metadataMode;
        this.inferMetadata = inferMetadata;
        this.strictQuotedIdentifiers = strictQuotedIdentifiers;
    }

    /**
     * The default options: metadata is authoritative and no metadata is inferred.
     */
    public static LineageOptions defaults() {
        return DEFAULT;
    }

    /**
     * Options with an explicit metadata mode and inference flag.
     */
    public static LineageOptions of(MetadataMode metadataMode, boolean inferMetadata) {
        return of(metadataMode, inferMetadata, false);
    }

    /**
     * Options with an explicit metadata mode, inference flag, and strict-quoting flag.
     */
    public static LineageOptions of(MetadataMode metadataMode, boolean inferMetadata,
            boolean strictQuotedIdentifiers) {
        if (metadataMode == MetadataMode.STRICT && !inferMetadata
                && !strictQuotedIdentifiers) {
            return DEFAULT;
        }
        return new LineageOptions(metadataMode, inferMetadata,
                strictQuotedIdentifiers);
    }

    public MetadataMode getMetadataMode() {
        return metadataMode;
    }

    /**
     * Whether columns observed in earlier statements are remembered and used to resolve later
     * unqualified column references.
     */
    public boolean isInferMetadata() {
        return inferMetadata;
    }

    /**
     * Whether every table and column reference must be written with an explicit quoted
     * identifier. When enabled, an unquoted reference fails the statement.
     */
    public boolean isStrictQuotedIdentifiers() {
        return strictQuotedIdentifiers;
    }
}
