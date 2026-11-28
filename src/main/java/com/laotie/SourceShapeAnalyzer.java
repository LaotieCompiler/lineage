package com.laotie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;

/** Static and dynamic shape queries over the sources visible to a query block. */
final class SourceShapeAnalyzer {

    private final DmlLineage host;

    SourceShapeAnalyzer(DmlLineage host) {
        this.host = host;
    }

    boolean useVisiblePlan(List<SourceTable> sources,
            Map<String, List<SourceTable>> using) {
        if (using == null || using.isEmpty()) {
            return false;
        }
        return host.analysisContext() != null || allSourceNamesKnown(sources);
    }

    boolean allWidthKnown(List<SourceTable> sources) {
        for (SourceTable source : sources) {
            if (!sourceWidthKnown(source)) {
                return false;
            }
        }
        return true;
    }

    boolean sourceWidthKnown(SourceTable source) {
        if (source.metadataTable != null || source.outputWidthKnown) {
            return true;
        }
        return source.temporary
                && Boolean.TRUE.equals(host.state().targetWidthKnown.get(source.displayName));
    }

    int derivedWidth(String target, List<String> outputs, boolean widthKnown) {
        int width = outputs.size();
        if (widthKnown) {
            Integer known = host.state().targetMinWidth.get(target);
            if (known != null && known > 0) {
                width = Math.max(width, known);
            }
        }
        return width;
    }

    int sourceKnownWidth(SourceTable source) {
        if (source.metadataTable != null) {
            return Math.max(source.metadataTable.getFields().size(), 1);
        }
        int width = source.outputColumns.size();
        if (source.temporary
                && Boolean.TRUE.equals(host.state().targetWidthKnown.get(source.displayName))) {
            Integer known = host.state().targetMinWidth.get(source.displayName);
            if (known != null && known > 0) {
                width = Math.max(width, known);
            }
        }
        if (width > 0) {
            return width;
        }
        return Math.max(host.sourceColumnNames(source).size(), 1);
    }

    List<SourceTable> refineWithLearned(List<SourceTable> candidates,
            String rawColumn) {
        if (host.learnedMetadata() == null) {
            return candidates;
        }
        List<SourceTable> refined = new ArrayList<>();
        for (SourceTable source : candidates) {
            if (source.metadataTable != null || source.temporary || !source.learnable
                    || source.rawName == null) {
                refined.add(source);
                continue;
            }
            String key = host.learnedTableKey(source.rawName);
            if (host.learnedMetadata().hasNormalizedColumn(key, host.identifierKey(rawColumn))
                    || !host.learnedMetadata().knowsNormalizedTable(key)) {
                refined.add(source);
            }
        }
        return refined;
    }

    List<SourceTable> unknownColumnSources(List<SourceTable> sources,
            List<SourceTable> excluded) {
        List<SourceTable> unknown = new ArrayList<>();
        for (SourceTable source : sources) {
            if (excluded.contains(source) || host.sourceNamesKnown(source)) {
                continue;
            }
            unknown.add(source);
        }
        return unknown;
    }

    static List<SourceTable> allSources(List<SourceTable> first,
            List<SourceTable> second) {
        List<SourceTable> all = new ArrayList<>();
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    boolean allSourceNamesKnown(List<SourceTable> sources) {
        for (SourceTable source : sources) {
            if (!host.sourceNamesKnown(source)) {
                return false;
            }
        }
        return true;
    }

    static List<SourceTable> starOutputSources(StarOutput output) {
        List<SourceTable> sources = new ArrayList<>();
        for (StarColumn column : output.sources) {
            if (!sources.contains(column.source)) {
                sources.add(column.source);
            }
        }
        return sources;
    }

    static List<String> displayNames(List<SourceTable> sources) {
        List<String> names = new ArrayList<>();
        for (SourceTable source : sources) {
            names.add(source.displayName);
        }
        return names;
    }

    boolean starOutputWidthKnown(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            if (!host.state().stackSourceNode.isEmpty()) {
                return host.state().stackSourceNode.peek().widthKnown;
            }
            List<SourceTable> sources = host.currentSources();
            if (sources.isEmpty()) {
                return false;
            }
            for (SourceTable source : sources) {
                if (!source.outputWidthKnown) {
                    return false;
                }
            }
            return true;
        }
        if (ExpressionAnalyzer.isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = host.findByOwner(owner);
            if (source != null) {
                if (source.metadataTable != null || source.outputWidthKnown) {
                    return true;
                }
                return source.temporary
                        && Boolean.TRUE.equals(host.state().targetWidthKnown.get(source.displayName));
            }
            SourceNode binding = host.joinBinding(owner);
            return binding != null && binding.widthKnown;
        }
        return true;
    }

    boolean outputWidthKnown(SQLSelectItem item) {
        SQLExpr expr = item.getExpr();
        if (expr instanceof SQLAllColumnExpr || ExpressionAnalyzer.isStarProperty(expr)) {
            // A wildcard's width can be known even when its column names are not.
            return starOutputWidthKnown(expr);
        }
        return true;
    }

    boolean outputNamesKnown(SQLSelectItem item) {
        SQLExpr expr = item.getExpr();
        if (host.detachedMetadata() && hasUnknownStarSource(expr)) {
            return false;
        }
        if (expr instanceof SQLAllColumnExpr) {
            if (!host.state().stackSourceNode.isEmpty()) {
                return bindingNamesKnown(host.state().stackSourceNode.peek());
            }
            for (SourceTable source : host.currentSources()) {
                if (!sourceNamesKnown(source)) {
                    return false;
                }
            }
            return true;
        }
        if (ExpressionAnalyzer.isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = host.findByOwner(owner);
            if (source != null) {
                return sourceNamesKnown(source);
            }
            SourceNode binding = host.joinBinding(owner);
            return binding != null && bindingNamesKnown(binding);
        }
        return true;
    }

    boolean bindingNamesKnown(SourceNode binding) {
        if (!binding.widthKnown || binding.plan.size() != binding.width) {
            return false;
        }
        for (StarOutput output : binding.plan) {
            if (!output.nameKnown || !output.stable) {
                return false;
            }
        }
        return true;
    }

    boolean sourceColumnsKnown(SourceTable source) {
        return source.metadataTable != null || source.outputWidthKnown;
    }

    boolean hasUnknownStarSource(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            for (SourceTable source : host.currentSources()) {
                if (!sourceNamesKnown(source)) {
                    return true;
                }
            }
            return false;
        }
        if (ExpressionAnalyzer.isStarProperty(expr)) {
            SourceTable source = host.findByOwner(((SQLPropertyExpr) expr).getOwner());
            if (source != null) {
                return !sourceNamesKnown(source);
            }
            SourceNode binding = host.joinBinding(((SQLPropertyExpr) expr).getOwner());
            if (binding != null) {
                for (SourceTable candidate : binding.sources) {
                    if (!sourceNamesKnown(candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean sourceNamesKnown(SourceTable source) {
        return source.metadataTable != null || source.outputNamesKnown;
    }

    boolean sourceOutputNameKnown(SourceTable source, int position) {
        if (source.metadataTable != null) {
            return true;
        }
        return position >= 0 && position < source.outputNameKnown.size()
                && Boolean.TRUE.equals(source.outputNameKnown.get(position));
    }

    int columnCount(SourceTable source) {
        if (source.metadataTable != null) {
            return Math.max(source.metadataTable.getFields().size(), 1);
        }
        if (!source.outputColumns.isEmpty()) {
            return source.outputColumns.size();
        }
        return 1;
    }
}
