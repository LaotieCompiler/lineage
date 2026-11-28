package com.laotie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
}
