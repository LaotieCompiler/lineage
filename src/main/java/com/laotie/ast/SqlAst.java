package com.laotie.ast;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.laotie.model.metadata.MetadataCatalog;

/**
 * AST predicates shared by the analyzers and the strict-quoting validator.
 */
public final class SqlAst {

    private SqlAst() {
    }

    /**
     * True for {@code owner.*}. A quoted {@code "*"} is an ordinary column name, not
     * a wildcard.
     */
    public static boolean isStarProperty(SQLExpr expr) {
        if (!(expr instanceof SQLPropertyExpr)) {
            return false;
        }
        String rawName = ((SQLPropertyExpr) expr).getName();
        return "*".equals(MetadataCatalog.normalizeIdentifier(rawName))
                && !MetadataCatalog.isQuotedIdentifier(rawName);
    }
}
