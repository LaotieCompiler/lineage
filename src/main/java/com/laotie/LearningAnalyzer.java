package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;

import com.laotie.DmlLineage;
import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.SourceTable;
import com.laotie.dialect.DialectRules;
import com.laotie.model.metadata.MetadataCatalog;

/** Explicit-reference discovery for learned metadata. */
final class LearningAnalyzer {

    private final DmlLineage host;
    private Map<String, Object> learningBindings = new HashMap<>();

    LearningAnalyzer(DmlLineage host) {
        this.host = host;
    }

    /** A local short name that matches more than one table in the same frame. */
    private static final String LOCAL_AMBIGUOUS = "\u0000local-ambiguous";
    /** Separates the tables an alias/join binding may come from. */
    private static final String LOCAL_TABLE_SEPARATOR = "\u0002";
    /** Marks a name that resolves through a JOIN binding's visible plan. */
    private static final String LOCAL_BINDING = "\u0000binding";
    /** Join alias whose USING keys attribute to the listed sides. */
    private static final String LOCAL_BINDING_USING = "\u0000binding-using:";
    private int learningBindingSeq;
    static String encodeLocalPart(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' || ch == '=' || ch == ';'
                    || ch == LOCAL_TABLE_SEPARATOR.charAt(0)) {
                encoded.append('%').append(String.format("%02X", (int) ch));
            } else {
                encoded.append(ch);
            }
        }
        return encoded.toString();
    }

    static String decodeLocalPart(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length()) {
                try {
                    decoded.append((char) Integer.parseInt(
                            value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Keep the literal percent.
                }
            }
            decoded.append(ch);
        }
        return decoded.toString();
    }

    void learnExplicitReferences(SQLExpr expr) {
        learnExplicitReferences(expr, new HashMap<String, String>(),
                new HashSet<String>());
    }

    void learnExplicitReferences(SQLExpr expr, Map<String, String> locals) {
        learnExplicitReferences(expr, locals, new HashSet<String>());
    }

    void learnExplicitReferences(SQLExpr expr, Map<String, String> locals,
            Set<String> cteKeys) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (!isStarProperty(property)) {
                String rawName = property.getName();
                String ownerKey = property.getOwner() == null ? null
                        : host.qualifiedKey(property.getOwner().toString());
                if (ownerKey != null && locals.containsKey(ownerKey)) {
                    String learnedTable = locals.get(ownerKey);
                    if (learnedTable != null
                            && learnedTable.startsWith(LOCAL_BINDING_USING)) {
                        learnThroughUsingBinding(learnedTable, rawName);
                    } else if (learnedTable != null
                            && learnedTable.startsWith(LOCAL_BINDING + "#")) {
                        learnThroughBinding(learnedTable, rawName);
                    } else if (LOCAL_BINDING.equals(learnedTable)) {
                        // A syntactically declared alias whose plan is not built:
                        // nothing can be attributed safely.
                    } else if (learnedTable != null
                            && !LOCAL_AMBIGUOUS.equals(learnedTable)) {
                        host.pendingLearned().computeIfAbsent(learnedTable,
                                k -> new LinkedHashSet<String>())
                                .add(host.identifierKey(rawName));
                    }
                    return;
                }
                learnExplicitReference(property);
            }
            return;
        }
        if (expr instanceof SQLQueryExpr) {
            learnSelectExplicitReferences(((SQLQueryExpr) expr).getSubQuery(),
                    locals, cteKeys);
        } else if (expr instanceof SQLInSubQueryExpr) {
            SQLInSubQueryExpr in = (SQLInSubQueryExpr) expr;
            learnExplicitReferences(in.getExpr(), locals, cteKeys);
            learnSelectExplicitReferences(in.getSubQuery(), locals, cteKeys);
        } else if (expr instanceof SQLBinaryOpExpr) {
            learnExplicitReferences(((SQLBinaryOpExpr) expr).getLeft(), locals,
                    cteKeys);
            learnExplicitReferences(((SQLBinaryOpExpr) expr).getRight(), locals,
                    cteKeys);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                learnExplicitReferences(argument, locals, cteKeys);
            }
        } else if (expr instanceof SQLCastExpr) {
            learnExplicitReferences(((SQLCastExpr) expr).getExpr(), locals, cteKeys);
        } else if (expr instanceof SQLNotExpr) {
            learnExplicitReferences(((SQLNotExpr) expr).getExpr(), locals, cteKeys);
        } else if (expr instanceof SQLUnaryExpr) {
            learnExplicitReferences(((SQLUnaryExpr) expr).getExpr(), locals, cteKeys);
        } else if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            learnExplicitReferences(between.getTestExpr(), locals, cteKeys);
            learnExplicitReferences(between.getBeginExpr(), locals, cteKeys);
            learnExplicitReferences(between.getEndExpr(), locals, cteKeys);
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            learnExplicitReferences(caseExpr.getValueExpr(), locals, cteKeys);
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    learnExplicitReferences(caseItem.getConditionExpr(), locals,
                            cteKeys);
                    learnExplicitReferences(caseItem.getValueExpr(), locals, cteKeys);
                }
            }
            learnExplicitReferences(caseExpr.getElseExpr(), locals, cteKeys);
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) expr).getItems()) {
                learnExplicitReferences(element, locals, cteKeys);
            }
        } else if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            learnExplicitReferences(in.getExpr(), locals, cteKeys);
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    learnExplicitReferences(element, locals, cteKeys);
                }
            }
        } else if (expr instanceof SQLExtractExpr) {
            learnExplicitReferences(((SQLExtractExpr) expr).getValue(), locals,
                    cteKeys);
        }
    }

    void learnThroughUsingBinding(String marker, String rawName) {
        String columnKey = host.identifierKey(rawName);
        String payload = marker.substring(LOCAL_BINDING_USING.length());
        for (String entry : payload.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0
                    || !decodeLocalPart(entry.substring(0, eq)).equals(columnKey)) {
                continue;
            }
            String tables = entry.substring(eq + 1);
            if (tables.isEmpty()) {
                return;
            }
            // Split while still encoded: a separator inside a quoted name is %02.
            for (String encoded : tables.split(LOCAL_TABLE_SEPARATOR)) {
                String table = decodeLocalPart(encoded);
                if (!table.isEmpty()) {
                    host.pendingLearned().computeIfAbsent(table,
                            k -> new LinkedHashSet<String>())
                            .add(columnKey);
                }
            }
            return;
        }
    }

    private static boolean isStarProperty(SQLExpr expr) {
        if (!(expr instanceof SQLPropertyExpr)) {
            return false;
        }
        String rawName = ((SQLPropertyExpr) expr).getName();
        return "*".equals(MetadataCatalog.normalizeIdentifier(rawName))
                && !MetadataCatalog.isQuotedIdentifier(rawName);
    }

    void learnThroughBinding(String marker, String rawName) {
        Object resolved = learningBindings.get(marker);
        if (!(resolved instanceof SourceNode)) {
            return;
        }
        SourceNode binding = (SourceNode) resolved;
        String columnKey = host.identifierKey(rawName);
        List<StarOutput> matches = new ArrayList<>();
        for (StarOutput output : binding.plan) {
            for (StarColumn column : output.sources) {
                if (columnKey != null && columnKey.equals(column.key)) {
                    matches.add(output);
                    break;
                }
            }
        }
        if (matches.size() != 1) {
            // Several visible outputs (or none): no single table may be learned.
            return;
        }
        for (StarColumn column : matches.get(0).sources) {
            host.learnColumn(column.source,
                    column.quoted ? host.quoteForLearning(column.column) : column.column,
                    true);
        }
    }

    void learnExplicitReference(SQLPropertyExpr property) {
        // The caller resolves owners against local or inherited bindings only; an
        // unbound name is never guessed to be a physical table.
    }

    void learnSelectExplicitReferences(SQLSelect select,
            Map<String, String> inheritedLocals) {
        learnSelectExplicitReferences(select, inheritedLocals, new HashSet<String>());
    }

    void learnSelectExplicitReferences(SQLSelect select,
            Map<String, String> inheritedLocals, Set<String> inheritedCtes) {
        if (select == null) {
            return;
        }
        Map<String, String> locals = new HashMap<>(inheritedLocals);
        Set<String> ctes = new HashSet<>(inheritedCtes);
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            boolean recursive = Boolean.TRUE.equals(with.getRecursive());
            if (recursive) {
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    if (entry.getAlias() != null) {
                        ctes.add(host.qualifiedKey(entry.getAlias()));
                    }
                }
            }
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                // A non-recursive body still sees a physical table of the same name.
                learnSelectExplicitReferences(entry.getSubQuery(), locals, ctes);
                if (!recursive && entry.getAlias() != null) {
                    ctes.add(host.qualifiedKey(entry.getAlias()));
                }
            }
        }
        learnQueryExplicitReferences(select.getQuery(), locals, ctes);
    }

    void learnSelectExplicitReferences(SQLSelectQuery query,
            Map<String, String> locals, Set<String> ctes) {
        learnQueryExplicitReferences(query, locals, ctes);
    }

    void collectFromNames(SQLSelectQuery query, Set<String> names) {
        if (query instanceof SQLSelectQueryBlock) {
            collectFromNames(((SQLSelectQueryBlock) query).getFrom(), names);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            collectFromNames(union.getLeft(), names);
            collectFromNames(union.getRight(), names);
        }
    }

    void collectFromNames(SQLTableSource from, Set<String> names) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            collectFromNames(join.getLeft(), names);
            collectFromNames(join.getRight(), names);
        } else if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            String name = host.extractTableName(table);
            if (table.getAlias() != null) {
                names.add(host.qualifiedKey(table.getAlias()));
            } else if (name != null) {
                names.add(host.qualifiedKey(name));
                if (!host.isSingleSegmentName(name)) {
                    names.add(host.qualifiedKey(host.lastSegment(name)));
                }
            }
        } else if (from instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) from;
            if (subquery.getAlias() != null) {
                names.add(host.qualifiedKey(subquery.getAlias()));
            }
        } else if (from instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) from;
            if (union.getAlias() != null) {
                names.add(host.qualifiedKey(union.getAlias()));
            }
        }
    }

    void learnQueryExplicitReferences(SQLSelectQuery query,
            Map<String, String> inheritedLocals, Set<String> ctes) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock block = (SQLSelectQueryBlock) query;
            Map<String, String> locals = new HashMap<>(inheritedLocals);
            Map<String, String> frameLocals = new HashMap<>();
            collectLocalNames(block.getFrom(), frameLocals, ctes);
            // A local table shadows an inherited short name.
            locals.putAll(frameLocals);
            Map<String, Object> savedBindings = new HashMap<>(learningBindings);
            for (Map.Entry<String, String> local : frameLocals.entrySet()) {
                if (LOCAL_BINDING.equals(local.getValue())) {
                    // A same-named local join alias has its own plan.
                    learningBindings.put(local.getKey(), null);
                }
            }
            try {
                // A non-LATERAL derived body and a JOIN ON see the enclosing scope,
                // not the other sibling sources of this FROM.
                learnDerivedBodies(block.getFrom(), inheritedLocals, ctes);
                Set<String> fromNames = new HashSet<>();
                collectFromNames(block.getFrom(), fromNames);
                learnJoinConditions(block.getFrom(), inheritedLocals, ctes, fromNames);
                for (SQLSelectItem item : block.getSelectList()) {
                    learnExplicitReferences(item.getExpr(), locals, ctes);
                }
                learnExplicitReferences(block.getWhere(), locals, ctes);
            } finally {
                learningBindings = savedBindings;
            }
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            // Each branch has its own FROM aliases; one branch must not shadow the
            // aliases of another.
            learnQueryExplicitReferences(union.getLeft(),
                    new HashMap<>(inheritedLocals), ctes);
            learnQueryExplicitReferences(union.getRight(),
                    new HashMap<>(inheritedLocals), ctes);
        }
    }

    void learnDerivedBodies(SQLTableSource from,
            Map<String, String> inheritedLocals, Set<String> ctes) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            learnDerivedBodies(join.getLeft(), inheritedLocals, ctes);
            learnDerivedBodies(join.getRight(), inheritedLocals, ctes);
        } else if (from instanceof SQLSubqueryTableSource) {
            learnSelectExplicitReferences(((SQLSubqueryTableSource) from).getSelect(),
                    inheritedLocals, ctes);
        } else if (from instanceof SQLUnionQueryTableSource) {
            learnSelectExplicitReferences(((SQLUnionQueryTableSource) from).getUnion(),
                    inheritedLocals, ctes);
        }
    }

    Map<String, String> outerBlockLocals(boolean includeCurrentFrame) {
        Map<String, String> locals = new HashMap<>();
        int top = includeCurrentFrame ? host.state().stackSources.size() - 1
                : host.state().stackSources.size() - 2;
        // Far frames first, so a nearer binding overrides an outer one.
        for (int frame = 0; frame <= top; frame++) {
            Map<String, String> frameLocals = new HashMap<>();
            Set<SourceTable> hidden = frame < host.state().stackHiddenSources.size()
                    ? host.state().stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            for (SourceTable source : host.state().stackSources.get(frame)) {
                if (hidden.contains(source)) {
                    continue;
                }
                String learnedTable = source.temporary || !source.learnable
                        || source.rawName == null
                        ? null : host.learnedTableKey(source.rawName);
                if (source.alias != null) {
                    // An alias hides the original table name.
                    putLocal(frameLocals, host.qualifiedKey(source.alias), learnedTable);
                    continue;
                }
                for (String reference : new String[] {source.displayName,
                        source.rawName, source.implicitName}) {
                    if (reference != null) {
                        putLocal(frameLocals, host.qualifiedKey(reference), learnedTable);
                    }
                }
                if (source.rawName != null && !host.isSingleSegmentName(source.rawName)) {
                    putLocal(frameLocals, host.qualifiedKey(host.lastSegment(source.rawName)),
                            learnedTable);
                }
            }
            if (frame < host.state().stackJoinBindings.size()) {
                for (Map.Entry<String, SourceNode> binding
                        : host.state().stackJoinBindings.get(frame).entrySet()) {
                    putLocal(frameLocals, binding.getKey(),
                            bindingToken(binding.getValue()));
                }
            }
            locals.putAll(frameLocals);
        }
        return locals;
    }

    String bindingToken(SourceNode binding) {
        String token = LOCAL_BINDING + "#" + (++learningBindingSeq);
        learningBindings.put(token, binding);
        return token;
    }

    private static void putLocal(Map<String, String> locals, String key,
            String learnedTable) {
        if (key == null) {
            return;
        }
        if (locals.containsKey(key)
                && !String.valueOf(learnedTable).equals(
                        String.valueOf(locals.get(key)))) {
            locals.put(key, LOCAL_AMBIGUOUS);
            return;
        }
        locals.put(key, learnedTable);
    }

    void learnNoFromExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        Map<String, Object> savedBindings = new HashMap<>(learningBindings);
        if (!host.state().stackJoinBindings.isEmpty()) {
            learningBindings.putAll(host.state().stackJoinBindings.peek());
        }
        try {
            Set<String> ctes = host.currentCteKeys();
            for (SQLSelectItem item : block.getSelectList()) {
                learnExplicitReferences(item.getExpr(), inherited, ctes);
            }
            learnExplicitReferences(block.getWhere(), inherited, ctes);
        } finally {
            learningBindings = savedBindings;
        }
    }

    void learnBlockExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        Map<String, Object> savedBindings = new HashMap<>(learningBindings);
        if (!host.state().stackJoinBindings.isEmpty()) {
            learningBindings.putAll(host.state().stackJoinBindings.peek());
        }
        try {
            learnBlockExplicitReferencesInScope(block, inherited);
        } finally {
            learningBindings = savedBindings;
        }
    }

    /**
     * Add a side's table only when the side is exactly one physical FROM instance.
     */
    void addSideInstances(Set<String> sides, SQLTableSource side,
            Set<String> ctes) {
        List<String> instances = sourceInstances(side, ctes);
        if (instances != null && instances.size() == 1) {
            sides.add(instances.get(0));
        }
    }

    List<String> sourceInstances(SQLTableSource from, Set<String> ctes) {
        if (from == null) {
            return null;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            List<String> left = sourceInstances(join.getLeft(), ctes);
            List<String> right = sourceInstances(join.getRight(), ctes);
            if (left == null || right == null) {
                return null;
            }
            List<String> instances = new ArrayList<>(left);
            instances.addAll(right);
            return instances;
        }
        if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            if (!host.isPhysicalTableExpression(table)) {
                return null;
            }
            String name = host.extractTableName(table);
            if (name == null) {
                return null;
            }
            if (host.isSingleSegmentName(name)
                    && ctes.contains(host.qualifiedKey(name))) {
                // A CTE is not a physical table instance.
                return null;
            }
            return new ArrayList<>(java.util.Collections.singletonList(
                    host.learnedTableKey(name)));
        }
        return null;
    }

    String usingBindingMark(SQLJoinTableSource join,
            Map<String, String> childLocals, Set<String> ctes) {
        if (join.getUsing() == null || join.getUsing().isEmpty()) {
            return LOCAL_BINDING;
        }
        Map<String, String> leftLocals = new HashMap<>();
        Map<String, String> rightLocals = new HashMap<>();
        collectLocalNames(join.getLeft(), leftLocals, ctes);
        collectLocalNames(join.getRight(), rightLocals, ctes);
        StringBuilder mark = new StringBuilder(LOCAL_BINDING_USING);
        for (SQLExpr expr : join.getUsing()) {
            String key = host.identifierKey(expr.toString());
            Set<String> sides = new LinkedHashSet<>();
            switch (join.getJoinType()) {
                case LEFT_OUTER_JOIN:
                case LEFT_SEMI_JOIN:
                case LEFT_ANTI_JOIN:
                    addSideInstances(sides, join.getLeft(), ctes);
                    break;
                case RIGHT_OUTER_JOIN:
                    addSideInstances(sides, join.getRight(), ctes);
                    break;
                default:
                    addSideInstances(sides, join.getLeft(), ctes);
                    addSideInstances(sides, join.getRight(), ctes);
                    break;
            }
            if (sides.isEmpty()) {
                continue;
            }
            if (mark.length() > LOCAL_BINDING_USING.length()) {
                mark.append(';');
            }
            List<String> encodedSides = new ArrayList<>();
            for (String side : sides) {
                encodedSides.add(encodeLocalPart(side));
            }
            mark.append(encodeLocalPart(key)).append('=')
                    .append(String.join(LOCAL_TABLE_SEPARATOR, encodedSides));
        }
        return mark.toString();
    }

    void learnBlockExplicitReferencesInScope(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        Set<String> ctes = host.currentCteKeys();
        Map<String, String> frameLocals = new HashMap<>();
        collectLocalNames(block.getFrom(), frameLocals, ctes);
        upgradeBindings(block.getFrom(), frameLocals);
        Set<String> fromNames = new HashSet<>();
        collectFromNames(block.getFrom(), fromNames);
        learnDerivedBodies(block.getFrom(), inherited, ctes);
        learnJoinConditions(block.getFrom(), inherited, ctes, fromNames);
        Map<String, String> locals = new HashMap<>(inherited);
        locals.putAll(frameLocals);
        for (SQLSelectItem item : block.getSelectList()) {
            learnExplicitReferences(item.getExpr(), locals, ctes);
        }
        learnExplicitReferences(block.getWhere(), locals, ctes);
    }

    void upgradeBindings(SQLTableSource from, Map<String, String> locals) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            if (join.getAlias() != null) {
                String key = host.qualifiedKey(join.getAlias());
                String value = locals.get(key);
                SourceNode binding = host.state().tableSourceBindings.get(join);
                if (binding != null && value != null
                        && (LOCAL_BINDING.equals(value)
                                || value.startsWith(LOCAL_BINDING_USING))) {
                    locals.put(key, bindingToken(binding));
                }
            }
            if (join.getAlias() == null) {
                // A join alias hides every qualifier inside its subtree, so those
                // child aliases must not take this scope's names.
                upgradeBindings(join.getLeft(), locals);
                upgradeBindings(join.getRight(), locals);
            }
        }
    }

    void learnJoinConditions(SQLTableSource from, Map<String, String> locals,
            Set<String> ctes, Set<String> fromNames) {
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            Map<String, String> subtree = new HashMap<>();
            collectLocalNames(join.getLeft(), subtree, ctes);
            collectLocalNames(join.getRight(), subtree, ctes);
            upgradeBindings(join.getLeft(), subtree);
            upgradeBindings(join.getRight(), subtree);
            Map<String, String> conditionLocals = new HashMap<>(locals);
            conditionLocals.putAll(subtree);
            for (String name : fromNames) {
                if (!subtree.containsKey(name) && !locals.containsKey(name)) {
                    // A source outside this join's subtree is not visible in ON.
                    conditionLocals.put(name, LOCAL_AMBIGUOUS);
                }
            }
            // A child join's ON cannot see the parent join's other side.
            learnJoinConditions(join.getLeft(), locals, ctes, fromNames);
            learnJoinConditions(join.getRight(), locals, ctes, fromNames);
            if (join.getCondition() != null) {
                // An ON condition sees the joined subtree's aliases even when the
                // whole join is aliased for the outer query.
                learnExplicitReferences(join.getCondition(), conditionLocals, ctes);
            }
        }
    }

    void collectLocalNames(SQLTableSource from, Map<String, String> locals,
            Set<String> ctes) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            Map<String, String> childLocals = join.getAlias() != null
                    ? new HashMap<String, String>() : locals;
            collectLocalNames(join.getLeft(), childLocals, ctes);
            collectLocalNames(join.getRight(), childLocals, ctes);
            if (join.getAlias() != null) {
                // An alias hides every qualifier inside the joined subtree. A USING
                // key is attributable to its sides; other columns need the plan.
                locals.put(host.qualifiedKey(join.getAlias()),
                        usingBindingMark(join, childLocals, ctes));
            }
        } else if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            boolean physical = host.isPhysicalTableExpression(table);
            String name = physical ? host.extractTableName(table)
                    : host.implicitRelationName(table);
            String reference = table.getAlias() != null
                    ? table.getAlias() : name;
            if (reference != null && !physical) {
                // A table function exposes its call name but owns no physical columns;
                // it still has to shadow an outer alias of the same name.
                putLocal(locals, host.qualifiedKey(reference), null);
                return;
            }
            if (reference != null) {
                String referenceKey = host.qualifiedKey(reference);
                String nameKey = name == null ? null : host.qualifiedKey(name);
                if (ctes.contains(nameKey)) {
                    // A CTE reference, possibly through an alias.
                    locals.put(referenceKey, null);
                } else {
                    String learnedTable = name == null ? null : host.learnedTableKey(name);
                    if (locals.containsKey(referenceKey)
                            && !String.valueOf(learnedTable).equals(
                                    String.valueOf(locals.get(referenceKey)))) {
                        locals.put(referenceKey, LOCAL_AMBIGUOUS);
                        return;
                    }
                    locals.put(referenceKey, learnedTable);
                    if (name != null && !host.isSingleSegmentName(name)
                            && table.getAlias() == null) {
                        // An unaliased table can also be referenced by its short name
                        // inside this scope; an alias hides the original name.
                        String shortKey = host.qualifiedKey(host.lastSegment(name));
                        if (locals.containsKey(shortKey)
                                && !String.valueOf(learnedTable).equals(
                                        String.valueOf(locals.get(shortKey)))) {
                            locals.put(shortKey, LOCAL_AMBIGUOUS);
                        } else {
                            locals.put(shortKey, learnedTable);
                        }
                    }
                }
            }
        } else if (from instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) from;
            if (subquery.getAlias() != null) {
                locals.put(host.qualifiedKey(subquery.getAlias()), null);
            }
        } else if (from instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) from;
            if (union.getAlias() != null) {
                locals.put(host.qualifiedKey(union.getAlias()), null);
            }
        }
    }

    void learnColumn(SourceTable source, String columnName) {
        learnColumn(source, columnName, false);
    }

    void learnColumn(SourceTable source, String columnName, boolean explicit) {
        if (host.state().learningDisabled || host.learnedMetadata() == null || source == null
                || source.temporary || !source.learnable
                || source.rawName == null || columnName == null
                || host.state().metadataPass && !explicit) {
            return;
        }
        String key = learnedTableKey(source.rawName);
        host.pendingLearned().computeIfAbsent(key, k -> new LinkedHashSet<String>())
                .add(host.identifierKey(columnName));
    }

    String quoteForLearning(String name) {
        return DialectRules.of(host.currentDialect()).quoteForLearning(name);
    }

    Set<String> currentCteKeys() {
        Set<String> ctes = new HashSet<>();
        for (Map<String, String> frame : host.state().cteScopes) {
            ctes.addAll(frame.keySet());
        }
        return ctes;
    }

    String learnedTableKey(String rawName) {
        StringBuilder key = new StringBuilder();
        if (host.sessionDatabase() != null) {
            key.append(host.identifierKey(host.sessionDatabase())).append('\u0001');
        }
        if (host.sessionSchema() != null) {
            key.append(host.sessionSchema()).append('\u0001');
        }
        key.append(host.qualifiedKey(rawName));
        return key.toString();
    }

    void publishLearned() {
        if (host.state().learningDisabled) {
            host.pendingLearned().clear();
            return;
        }
        if (host.learnedMetadata() != null) {
            for (Map.Entry<String, Set<String>> entry : host.pendingLearned().entrySet()) {
                for (String column : entry.getValue()) {
                    host.learnedMetadata().learnNormalized(entry.getKey(), column);
                }
            }
        }
        host.pendingLearned().clear();
    }
}
