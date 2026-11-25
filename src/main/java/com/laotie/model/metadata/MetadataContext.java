package com.laotie.model.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable metadata context: a catalog snapshot plus session defaults and an effective
 * search path.
 *
 * <p>
 * The context captures the snapshot version and checksum at creation time. Mutating the original
 * catalog afterwards does not affect the context.
 */
public class MetadataContext {
    private final MetadataCatalog catalog;
    private final String instance;
    private final String database;
    private final List<String> schemas;
    private final long metadataVersion;
    private final String metadataChecksum;

    private MetadataContext(MetadataCatalog catalog, String instance, String database,
            List<String> schemas) {
        this.catalog = catalog;
        this.instance = instance;
        this.database = database;
        this.schemas = Collections.unmodifiableList(new ArrayList<>(schemas));
        this.metadataVersion = catalog.getVersion();
        this.metadataChecksum = catalog.getChecksum();
    }

    private MetadataContext(MetadataCatalog snapshot, String instance, String database,
            List<String> schemas, long metadataVersion, String metadataChecksum) {
        this.catalog = snapshot;
        this.instance = instance;
        this.database = database;
        this.schemas = Collections.unmodifiableList(new ArrayList<>(schemas));
        this.metadataVersion = metadataVersion;
        this.metadataChecksum = metadataChecksum;
    }

    public static MetadataContext of(MetadataCatalog catalog) {
        MetadataCatalog snapshot = catalog.snapshot();
        return new MetadataContext(snapshot, null, null, Collections.<String>emptyList());
    }

    public static MetadataContext of(MetadataCatalog catalog, String instance, String database,
            String schema) {
        MetadataCatalog snapshot = catalog.snapshot();
        List<String> schemas = new ArrayList<>();
        if (schema != null) {
            schemas.add(schema);
        }
        return new MetadataContext(snapshot, instance, database, schemas);
    }

    /**
     * Derive a session context with updated defaults, sharing the same snapshot and labels.
     */
    public MetadataContext withSession(String instance, String database, List<String> schemas) {
        return new MetadataContext(catalog, instance, database, schemas, metadataVersion,
                metadataChecksum);
    }

    public MetadataCatalog getCatalog() {
        return catalog;
    }

    public String getInstance() {
        return instance;
    }

    public String getDatabase() {
        return database;
    }

    /**
     * The first schema of the effective search path, or null.
     */
    public String getSchema() {
        return schemas.isEmpty() ? null : schemas.get(0);
    }

    public List<String> getSchemas() {
        return schemas;
    }

    public long getMetadataVersion() {
        return metadataVersion;
    }

    public String getMetadataChecksum() {
        return metadataChecksum;
    }
}
