package com.laotie;

import com.laotie.model.metadata.MetadataMode;

/**
 * Options controlling how a lineage scan treats metadata.
 */
public final class LineageOptions {

    private static final LineageOptions DEFAULT =
            new LineageOptions(MetadataMode.STRICT, false);

    private final MetadataMode metadataMode;
    private final boolean inferMetadata;

    private LineageOptions(MetadataMode metadataMode, boolean inferMetadata) {
        this.metadataMode = metadataMode == null ? MetadataMode.STRICT : metadataMode;
        this.inferMetadata = inferMetadata;
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
        if (metadataMode == MetadataMode.STRICT && !inferMetadata) {
            return DEFAULT;
        }
        return new LineageOptions(metadataMode, inferMetadata);
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
}
