package com.laotie.dialect;

import com.alibaba.druid.DbType;
import com.laotie.identifier.Identifier;
import com.laotie.identifier.IdentifierCase;
import com.laotie.identifier.Identifiers;

/**
 * Dialect-specific identifier and unit-keyword rules.
 *
 * <p>
 * This is the strategy seam for behaviour that used to be scattered across
 * {@code DmlLineage}. Implementations are immutable and stateless.
 * </p>
 */
public interface DialectRules {

    DbType dialect();

    /** Canonical map key for an identifier reference. */
    String key(Identifier identifier);

    /** Canonical spelling used for display/learning identities. */
    String canonical(Identifier identifier);

    /** Quote a name for learned-metadata keys. */
    default String quoteForLearning(String name) {
        if (dialect() == DbType.mysql || dialect() == DbType.mariadb) {
            return Identifiers.quoteBacktick(name);
        }
        return Identifiers.quoteDouble(name);
    }

    /** Whether a function call may take a unit keyword as its first argument. */
    default boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method);
    }

    /** Derby only accepts the JDBC escape form's SQL_TSI_* units. */
    default boolean requiresJdbcTimestampUnit() {
        return false;
    }

    default boolean isUnitKeyword(String name) {
        return UnitKeywords.isUnit(name);
    }

    default boolean isJdbcUnitKeyword(String name) {
        return UnitKeywords.isJdbcUnit(name);
    }

    static String foldCase(String value) {
        return IdentifierCase.fold(value);
    }

    static DialectRules of(DbType dialect) {
        if (dialect == null) {
            return DefaultDialectRules.INSTANCE;
        }
        switch (dialect) {
            case mysql:
            case mariadb:
                return MySqlDialectRules.INSTANCE;
            case postgresql:
                return PostgresDialectRules.INSTANCE;
            case hive:
            case antspark:
                return HiveDialectRules.INSTANCE;
            case sqlserver:
                return SqlServerDialectRules.INSTANCE;
            case sybase:
                return SybaseDialectRules.INSTANCE;
            case h2:
                return H2DialectRules.INSTANCE;
            case derby:
                return DerbyDialectRules.INSTANCE;
            case hsql:
                return HsqlDialectRules.INSTANCE;
            default:
                return DefaultDialectRules.INSTANCE;
        }
    }
}

enum KeyStyle {
    LOWER_ALWAYS,
    LOWER_UNQUOTED,
    FOLD_ALWAYS,
    UPPER_UNQUOTED
}

enum CanonicalStyle {
    KEEP,
    LOWER_UNQUOTED,
    UPPER_UNQUOTED
}

abstract class BaseDialectRules implements DialectRules {
    private final DbType dialect;
    private final KeyStyle keyStyle;
    private final CanonicalStyle canonicalStyle;

    BaseDialectRules(DbType dialect, KeyStyle keyStyle,
            CanonicalStyle canonicalStyle) {
        this.dialect = dialect;
        this.keyStyle = keyStyle;
        this.canonicalStyle = canonicalStyle;
    }

    @Override
    public DbType dialect() {
        return dialect;
    }

    @Override
    public String key(Identifier identifier) {
        if (identifier == null) {
            return null;
        }
        String name = identifier.name();
        switch (keyStyle) {
            case LOWER_ALWAYS:
                return IdentifierCase.lower(name);
            case LOWER_UNQUOTED:
                return identifier.isQuoted() ? name
                        : IdentifierCase.lower(name);
            case FOLD_ALWAYS:
                return IdentifierCase.fold(name);
            default:
                return identifier.isQuoted() ? name
                        : IdentifierCase.upper(name);
        }
    }

    @Override
    public String canonical(Identifier identifier) {
        if (identifier == null) {
            return null;
        }
        String name = identifier.name();
        switch (canonicalStyle) {
            case KEEP:
                return name;
            case LOWER_UNQUOTED:
                return identifier.isQuoted() ? name : name.toLowerCase();
            default:
                return identifier.isQuoted() ? name : name.toUpperCase();
        }
    }
}

final class DefaultDialectRules extends BaseDialectRules {
    static final DefaultDialectRules INSTANCE = new DefaultDialectRules();

    private DefaultDialectRules() {
        super(null, KeyStyle.UPPER_UNQUOTED, CanonicalStyle.UPPER_UNQUOTED);
    }

}

final class MySqlDialectRules extends BaseDialectRules {
    static final MySqlDialectRules INSTANCE = new MySqlDialectRules();

    private MySqlDialectRules() {
        super(DbType.mysql, KeyStyle.LOWER_ALWAYS, CanonicalStyle.KEEP);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method)
                || "GET_FORMAT".equalsIgnoreCase(method);
    }

}

final class PostgresDialectRules extends BaseDialectRules {
    static final PostgresDialectRules INSTANCE = new PostgresDialectRules();

    private PostgresDialectRules() {
        super(DbType.postgresql, KeyStyle.LOWER_UNQUOTED,
                CanonicalStyle.LOWER_UNQUOTED);
    }

}

final class HiveDialectRules extends BaseDialectRules {
    static final HiveDialectRules INSTANCE = new HiveDialectRules();

    private HiveDialectRules() {
        super(DbType.hive, KeyStyle.FOLD_ALWAYS, CanonicalStyle.UPPER_UNQUOTED);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method);
    }

}

final class SqlServerDialectRules extends BaseDialectRules {
    static final SqlServerDialectRules INSTANCE = new SqlServerDialectRules();

    private SqlServerDialectRules() {
        super(DbType.sqlserver, KeyStyle.UPPER_UNQUOTED,
                CanonicalStyle.UPPER_UNQUOTED);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method)
                || "DATEADD".equalsIgnoreCase(method)
                || "DATEDIFF".equalsIgnoreCase(method)
                || "DATEDIFF_BIG".equalsIgnoreCase(method)
                || "DATEPART".equalsIgnoreCase(method)
                || "DATENAME".equalsIgnoreCase(method)
                || "DATETRUNC".equalsIgnoreCase(method)
                || "DATE_BUCKET".equalsIgnoreCase(method);
    }

}

final class SybaseDialectRules extends BaseDialectRules {
    static final SybaseDialectRules INSTANCE = new SybaseDialectRules();

    private SybaseDialectRules() {
        super(DbType.sybase, KeyStyle.UPPER_UNQUOTED,
                CanonicalStyle.UPPER_UNQUOTED);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method)
                || "DATEADD".equalsIgnoreCase(method)
                || "DATEDIFF".equalsIgnoreCase(method)
                || "DATEPART".equalsIgnoreCase(method)
                || "DATENAME".equalsIgnoreCase(method);
    }

}

final class H2DialectRules extends BaseDialectRules {
    static final H2DialectRules INSTANCE = new H2DialectRules();

    private H2DialectRules() {
        super(DbType.h2, KeyStyle.UPPER_UNQUOTED,
                CanonicalStyle.UPPER_UNQUOTED);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method)
                || "DATEADD".equalsIgnoreCase(method)
                || "DATEDIFF".equalsIgnoreCase(method)
                || "DATE_TRUNC".equalsIgnoreCase(method);
    }

}

final class DerbyDialectRules extends BaseDialectRules {
    static final DerbyDialectRules INSTANCE = new DerbyDialectRules();

    private DerbyDialectRules() {
        super(DbType.derby, KeyStyle.UPPER_UNQUOTED,
                CanonicalStyle.UPPER_UNQUOTED);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method);
    }

    @Override
    public boolean requiresJdbcTimestampUnit() {
        return true;
    }
}

final class HsqlDialectRules extends BaseDialectRules {
    static final HsqlDialectRules INSTANCE = new HsqlDialectRules();

    private HsqlDialectRules() {
        super(DbType.hsql, KeyStyle.UPPER_UNQUOTED,
                CanonicalStyle.UPPER_UNQUOTED);
    }

    @Override
    public boolean allowsUnitFunction(String method) {
        return "EXTRACT".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method);
    }

}
