package com.laotie.analysis.model;

import java.util.List;
import java.util.Map;

public final class SourceNode {
    public final List<SourceTable> sources;
    public final List<StarOutput> plan;
    public final boolean knownColumns;
    public final boolean widthKnown;
    public final int width;
    public final int minWidth;
    public final int usingCount;
    public final int rightBoundary;
    public final List<SourceTable> unknownLeftSources;
    public final List<SourceTable> unknownRightSources;
    public final Map<SourceTable, Integer> unknownSectionEnd;
    public final SourceNode leftNode;
    public final SourceNode rightNode;

    public SourceNode(List<SourceTable> sources, List<StarOutput> plan,
            boolean knownColumns, boolean widthKnown, int width, int minWidth,
            int usingCount, int rightBoundary, List<SourceTable> unknownLeftSources,
            List<SourceTable> unknownRightSources,
            Map<SourceTable, Integer> unknownSectionEnd) {
        this(sources, plan, knownColumns, widthKnown, width, minWidth, usingCount,
                rightBoundary, unknownLeftSources, unknownRightSources,
                unknownSectionEnd, null, null);
    }

    public SourceNode(List<SourceTable> sources, List<StarOutput> plan,
            boolean knownColumns, boolean widthKnown, int width, int minWidth,
            int usingCount, int rightBoundary, List<SourceTable> unknownLeftSources,
            List<SourceTable> unknownRightSources,
            Map<SourceTable, Integer> unknownSectionEnd, SourceNode leftNode,
            SourceNode rightNode) {
        this.sources = sources;
        this.plan = plan;
        this.knownColumns = knownColumns;
        this.widthKnown = widthKnown;
        this.width = width;
        this.minWidth = minWidth;
        this.usingCount = usingCount;
        this.rightBoundary = rightBoundary;
        this.unknownLeftSources = unknownLeftSources;
        this.unknownRightSources = unknownRightSources;
        this.unknownSectionEnd = unknownSectionEnd;
        this.leftNode = leftNode;
        this.rightNode = rightNode;
    }
}
