package com.laotie.analysis.model;

import java.util.List;

import com.laotie.model.metadata.Table;

public final class SourceTable {
    public final String displayName;
    public final String writtenName;
    public final String rawName;
    public final String alias;
    public final Table metadataTable;
    public final boolean temporary;
    public final boolean outputWidthKnown;
    public final boolean outputNamesKnown;
    public final List<String> outputColumns;
    public final List<String> outputKeys;
    public final List<Boolean> outputNameKnown;
    public final List<SourceTable> outputUnknownGroups;
    public final int minOutputColumns;
    public final boolean wildcardOnly;
    public final boolean wildcardContribution;
    public final boolean learnable;
    public final String implicitName;

    public SourceTable(String displayName, String writtenName, String rawName, String alias,
            Table metadataTable, boolean temporary, boolean outputWidthKnown,
            boolean outputNamesKnown, List<String> outputColumns, List<String> outputKeys,
            List<Boolean> outputNameKnown, int minOutputColumns,
            List<SourceTable> outputUnknownGroups) {
        this(displayName, writtenName, rawName, alias, metadataTable, temporary,
                outputWidthKnown, outputNamesKnown, outputColumns, outputKeys,
                outputNameKnown, minOutputColumns, outputUnknownGroups, false, false);
    }

    public SourceTable(String displayName, String writtenName, String rawName, String alias,
            Table metadataTable, boolean temporary, boolean outputWidthKnown,
            boolean outputNamesKnown, List<String> outputColumns, List<String> outputKeys,
            List<Boolean> outputNameKnown, int minOutputColumns,
            List<SourceTable> outputUnknownGroups, boolean wildcardOnly,
            boolean wildcardContribution) {
        this(displayName, writtenName, rawName, alias, metadataTable, temporary,
                outputWidthKnown, outputNamesKnown, outputColumns, outputKeys,
                outputNameKnown, minOutputColumns, outputUnknownGroups, wildcardOnly,
                wildcardContribution, true, null);
    }

    public SourceTable(String displayName, String writtenName, String rawName, String alias,
            Table metadataTable, boolean temporary, boolean outputWidthKnown,
            boolean outputNamesKnown, List<String> outputColumns, List<String> outputKeys,
            List<Boolean> outputNameKnown, int minOutputColumns,
            List<SourceTable> outputUnknownGroups, boolean wildcardOnly,
            boolean wildcardContribution, boolean learnable, String implicitName) {
        this.displayName = displayName;
        this.writtenName = writtenName;
        this.rawName = rawName;
        this.alias = alias;
        this.metadataTable = metadataTable;
        this.temporary = temporary;
        this.outputWidthKnown = outputWidthKnown;
        this.outputNamesKnown = outputNamesKnown;
        this.outputColumns = outputColumns == null
                ? java.util.Collections.<String>emptyList() : outputColumns;
        this.outputKeys = outputKeys == null
                ? java.util.Collections.<String>emptyList() : outputKeys;
        this.outputNameKnown = outputNameKnown == null
                ? java.util.Collections.<Boolean>emptyList() : outputNameKnown;
        this.minOutputColumns = minOutputColumns;
        this.outputUnknownGroups = outputUnknownGroups == null
                ? java.util.Collections.<SourceTable>emptyList() : outputUnknownGroups;
        this.wildcardOnly = wildcardOnly;
        this.wildcardContribution = wildcardContribution;
        this.learnable = learnable;
        this.implicitName = implicitName;
    }
}
