package com.laotie.parse;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;

/**
 * Single-statement parser with the small dialect fallback the analyzer needs.
 */
public final class SqlParser {

    private SqlParser() {
    }

    /**
     * Spark SQL is Hive-compatible; the antspark parser does not accept some Hive
     * statements (for example INSERT OVERWRITE TABLE). Fall back to the Hive parser
     * only when the dialect's own parser rejects the statement.
     */
    public static SQLStatement parse(String sql, DbType dialect) {
        try {
            return SQLUtils.parseSingleStatement(sql, dialect);
        } catch (RuntimeException e) {
            if (dialect == DbType.antspark) {
                try {
                    return SQLUtils.parseSingleStatement(sql, DbType.hive);
                } catch (RuntimeException ignored) {
                    // Report the original dialect's error below.
                }
            }
            throw e;
        }
    }
}
