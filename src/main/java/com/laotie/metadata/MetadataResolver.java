package com.laotie.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.alibaba.druid.DbType;
import com.laotie.dialect.DialectRules;
import com.laotie.exception.AmbiguousTableException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.TableLocation;

/**
 * Resolves SQL table references against a catalog, taking explicit qualifiers,
 * session defaults and search paths into account.
 *
 * <p>
 * The resolver is immutable for the duration of one statement; create a new
 * instance whenever the session or context changes.
 * </p>
 */
public final class MetadataResolver {

    private final MetadataContext context;
    private final DbType dialect;
    private final String sessionDatabase;
    private final String sessionRawDatabase;
    private final List<String> sessionSchemas;
    private final List<String> sessionRawSchemas;
    private final boolean detached;

    public MetadataResolver(MetadataContext context, DbType dialect,
            String sessionDatabase, String sessionRawDatabase,
            List<String> sessionSchemas, List<String> sessionRawSchemas,
            boolean detached) {
        this.context = context;
        this.dialect = dialect;
        this.sessionDatabase = sessionDatabase;
        this.sessionRawDatabase = sessionRawDatabase;
        this.sessionSchemas = sessionSchemas == null
                ? java.util.Collections.<String>emptyList()
                : sessionSchemas;
        this.sessionRawSchemas = sessionRawSchemas == null
                ? java.util.Collections.<String>emptyList()
                : sessionRawSchemas;
        this.detached = detached;
    }

    private String effectiveDatabase() {
        return sessionDatabase != null ? sessionDatabase
                : context.getDatabase();
    }

    private List<String> effectiveSchemas() {
        if (!sessionRawSchemas.isEmpty()) {
            return sessionRawSchemas;
        }
        if (!sessionSchemas.isEmpty()) {
            return sessionSchemas;
        }
        return context.getSchemas();
    }

    public TableLocation resolve(String written) {
        MetadataCatalog catalog = context.getCatalog();
        String[] parts = MetadataCatalog.splitQualifiedName(written);
        boolean explicitInstance = parts.length >= 4;
        boolean explicitDatabase = dialect == DbType.mysql
                || dialect == DbType.mariadb
                        ? parts.length >= 2 : parts.length >= 3;
        boolean explicitSchema = hasExplicitSchema(written);
        boolean hiveFamily = dialect == DbType.hive
                || dialect == DbType.antspark;

        String instanceDefault = explicitInstance ? null
                : context.getInstance() != null ? context.getInstance()
                        : catalog.getDefaultInstance();
        String databaseDefault;
        if (explicitDatabase || hiveFamily) {
            databaseDefault = null;
        } else if (effectiveDatabase() != null) {
            databaseDefault = effectiveDatabase();
        } else {
            databaseDefault = catalog.getDefaultDatabase();
        }
        String schemaDefault;
        if (explicitSchema || !context.getSchemas().isEmpty()) {
            schemaDefault = null;
        } else if (hiveFamily) {
            if (effectiveDatabase() != null) {
                schemaDefault = null;
            } else if (catalog.getDefaultSchema() != null) {
                schemaDefault = catalog.getDefaultSchema();
            } else {
                schemaDefault = catalog.getDefaultDatabase();
            }
        } else {
            schemaDefault = catalog.getDefaultSchema();
        }

        String[] preserved = MetadataCatalog
                .splitQualifiedNamePreservingQuotes(written);
        String schemaRef = null;
        if (preserved.length >= 3) {
            schemaRef = preserved[preserved.length - 2];
        } else if (preserved.length == 2 && !(dialect == DbType.mysql
                || dialect == DbType.mariadb)) {
            schemaRef = preserved[0];
        }
        String tableRef = preserved[preserved.length - 1];
        boolean pgPublic = dialect == DbType.postgresql && explicitSchema
                && isPostgresPublic(schemaRef);
        String lookupReference = written;
        String lookupSchemaDefault = schemaDefault;
        if (pgPublic) {
            lookupReference = tableRef;
            lookupSchemaDefault = null;
            if (preserved.length >= 4) {
                instanceDefault = MetadataCatalog.normalizeIdentifier(
                        preserved[preserved.length - 4]);
                databaseDefault = MetadataCatalog.normalizeIdentifier(
                        preserved[preserved.length - 3]);
            } else if (preserved.length == 3) {
                databaseDefault = MetadataCatalog.normalizeIdentifier(
                        preserved[0]);
            }
        }

        List<TableLocation> filtered = catalog.findAllTables(lookupReference,
                instanceDefault, databaseDefault, lookupSchemaDefault);

        if (explicitInstance && preserved.length >= 4) {
            filtered = filterMatches(filtered, preserved[0],
                    TableLocation::getInstance);
        }
        if (explicitDatabase) {
            String databaseRef = null;
            if (preserved.length >= 4) {
                databaseRef = preserved[preserved.length - 3];
            } else if (preserved.length == 3) {
                databaseRef = preserved[0];
            } else if (preserved.length == 2
                    && (dialect == DbType.mysql
                            || dialect == DbType.mariadb)) {
                databaseRef = preserved[0];
            }
            if (databaseRef != null) {
                filtered = filterMatches(filtered, databaseRef,
                        TableLocation::getDatabase);
            }
        }

        if (explicitSchema && schemaRef != null) {
            filtered = filterMatches(filtered, schemaRef,
                    TableLocation::getSchema);
        }
        if (dialect == DbType.postgresql && explicitSchema) {
            List<TableLocation> exact = postgresTableMatches(filtered, tableRef);
            if (!exact.isEmpty() || MetadataCatalog.isQuotedIdentifier(tableRef)) {
                filtered = exact;
            }
        }

        if (!explicitSchema) {
            List<String> searchPath = effectiveSchemas();
            if (!searchPath.isEmpty()) {
                for (String schema : searchPath) {
                    List<TableLocation> inSchema = new ArrayList<>();
                    for (TableLocation candidate : filtered) {
                        if (schemaMatches(schema, candidate.getSchema())) {
                            inSchema.add(candidate);
                        }
                    }
                    if (dialect == DbType.postgresql) {
                        List<TableLocation> chosen = postgresTableMatches(
                                inSchema, tableRef);
                        if (!chosen.isEmpty()) {
                            return chosen.get(0);
                        }
                    } else {
                        for (TableLocation candidate : inSchema) {
                            return candidate;
                        }
                    }
                }
                return missingTable(written);
            }
            if (hiveFamily && effectiveDatabase() != null) {
                for (TableLocation candidate : filtered) {
                    if (schemaMatches(effectiveDatabase(),
                            candidate.getSchema())
                            || schemaMatches(effectiveDatabase(),
                                    candidate.getDatabase())) {
                        return candidate;
                    }
                }
                return missingTable(written);
            }
        }

        if (dialect == DbType.postgresql && !explicitSchema) {
            List<TableLocation> exact = postgresTableMatches(filtered, tableRef);
            if (!exact.isEmpty() || MetadataCatalog.isQuotedIdentifier(tableRef)) {
                filtered = exact;
            }
        }
        if (filtered.isEmpty()) {
            return missingTable(written);
        }
        if (filtered.size() > 1) {
            List<String> names = new ArrayList<>();
            for (TableLocation candidate : filtered) {
                names.add(candidate.getQualifiedName());
            }
            throw new AmbiguousTableException(written, names);
        }
        return filtered.get(0);
    }

    /**
     * Graph identity for an unqualified table when there is no catalog: prefix it
     * with the current database or first search-path schema.
     */
    public String physicalReference(String rawName) {
        String reference = rawName;
        String prefix = referencePrefix();
        if (prefix != null && rawName != null
                && isSingleSegmentName(rawName)) {
            reference = prefix + "." + rawName;
        }
        return canonicalPhysicalReference(reference);
    }

    private List<TableLocation> filterMatches(List<TableLocation> candidates,
            String reference, java.util.function.Function<TableLocation, String> part) {
        List<TableLocation> exact = new ArrayList<>();
        for (TableLocation candidate : candidates) {
            if (schemaMatches(reference, part.apply(candidate))) {
                exact.add(candidate);
            }
        }
        return exact;
    }

    private boolean hasExplicitSchema(String reference) {
        String[] parts = MetadataCatalog.splitQualifiedName(reference);
        if (parts.length >= 3) {
            return true;
        }
        if (parts.length == 2) {
            return !(dialect == DbType.mysql || dialect == DbType.mariadb);
        }
        return false;
    }

    private boolean schemaMatches(String reference, String actual) {
        if (reference == null) {
            return false;
        }
        if (actual == null) {
            // An unqualified catalog table lives in the default schema.
            if (dialect != DbType.postgresql) {
                return false;
            }
            boolean quotedDefault = MetadataCatalog.isQuotedIdentifier(reference);
            String defaultName = MetadataCatalog.normalizeIdentifier(reference);
            return quotedDefault ? "public".equals(defaultName)
                    : "public".equals(
                            defaultName.toLowerCase(Locale.ROOT));
        }
        boolean quoted = MetadataCatalog.isQuotedIdentifier(reference);
        String name = MetadataCatalog.normalizeIdentifier(reference);
        if (dialect == DbType.postgresql) {
            return quoted ? name.equals(actual)
                    : name.toLowerCase(Locale.ROOT).equals(actual);
        }
        if (dialect == DbType.mysql || dialect == DbType.mariadb
                || dialect == DbType.hive || dialect == DbType.antspark
                || dialect == DbType.sqlserver) {
            return DialectRules.foldCase(name).equals(
                    DialectRules.foldCase(actual));
        }
        return quoted ? name.equals(actual) : name.equalsIgnoreCase(actual);
    }

    private static boolean isPostgresPublic(String schemaRef) {
        if (schemaRef == null) {
            return false;
        }
        String name = MetadataCatalog.normalizeIdentifier(schemaRef);
        return MetadataCatalog.isQuotedIdentifier(schemaRef)
                ? "public".equals(name)
                : "public".equals(name.toLowerCase(Locale.ROOT));
    }

    private static List<TableLocation> postgresTableMatches(
            List<TableLocation> candidates, String tableRef) {
        String tableName = MetadataCatalog.normalizeIdentifier(tableRef);
        boolean quoted = MetadataCatalog.isQuotedIdentifier(tableRef);
        List<TableLocation> folded = new ArrayList<>();
        for (TableLocation candidate : candidates) {
            String actual = candidate.getTable().getName();
            if (quoted ? tableName.equals(actual)
                    : tableName.toLowerCase(Locale.ROOT).equals(actual)) {
                folded.add(candidate);
            }
        }
        if (!folded.isEmpty() || quoted) {
            return folded;
        }
        List<TableLocation> lenient = new ArrayList<>();
        for (TableLocation candidate : candidates) {
            if (!candidate.getTable().isQuotedName()
                    && tableName.equalsIgnoreCase(
                            candidate.getTable().getName())) {
                lenient.add(candidate);
            }
        }
        return lenient;
    }

    private TableLocation missingTable(String written) {
        if (detached) {
            return null;
        }
        throw new MetadataNotFoundException(written);
    }

    private String referencePrefix() {
        if (dialect == DbType.postgresql && !sessionRawSchemas.isEmpty()) {
            return sessionRawSchemas.get(0);
        }
        if (sessionDatabase != null) {
            return sessionRawDatabase != null ? sessionRawDatabase
                    : sessionDatabase;
        }
        if (!sessionRawSchemas.isEmpty()) {
            return sessionRawSchemas.get(0);
        }
        return null;
    }

    private String canonicalPhysicalReference(String raw) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(raw);
        StringBuilder reference = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                reference.append('.');
            }
            String part = parts[i];
            boolean quoted = MetadataCatalog.isQuotedIdentifier(part);
            String name = MetadataCatalog.normalizeIdentifier(part);
            if (!quoted) {
                // PostgreSQL folds unquoted identifiers to lower case, so A and a are
                // the same table. Other dialects keep their spelling here.
                reference.append(dialect == DbType.postgresql
                        ? name.toLowerCase(Locale.ROOT) : name);
                continue;
            }
            // A quoted identifier that folds to itself is the same as the unquoted
            // spelling (PostgreSQL "a" and a); one that does not is a distinct name.
            boolean special = name.contains(".") || name.contains("(") || name.contains(")");
            boolean folds = !special && name.equals(foldName(name));
            reference.append(folds ? name : part);
        }
        return reference.toString();
    }

    private String foldName(String name) {
        return dialect == DbType.mysql || dialect == DbType.mariadb
                || dialect == DbType.postgresql
                        ? name.toLowerCase(Locale.ROOT)
                        : name.toUpperCase(Locale.ROOT);
    }

    private static boolean isSingleSegmentName(String rawName) {
        return MetadataCatalog.splitQualifiedNamePreservingQuotes(rawName).length == 1;
    }
}
