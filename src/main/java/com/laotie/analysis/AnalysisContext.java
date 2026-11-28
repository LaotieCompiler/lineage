package com.laotie.analysis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.alibaba.druid.sql.ast.statement.SQLTableSource;

import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.WildcardSpan;

/**
 * Mutable state shared by the analysis passes for one statement.
 *
 * <p>
 * This is the first step of extracting the former field soup from
 * {@link DmlLineage}. Fields are intentionally direct during the migration;
 * behaviour-preserving accessors can replace them incrementally.
 * </p>
 */
public final class AnalysisContext {

    public Stack<String> stackTargetTable;
    public Stack<String> stackSourceTable;
    public Stack<String> stackTargetColumn;
    public Stack<List<SourceTable>> stackSources;
    public Stack<Map<String, List<SourceTable>>> stackUsing;
    public Stack<Map<String, SourceNode>> stackJoinBindings;
    public Map<Map<String, List<SourceTable>>, Map<String, List<SourceTable>>>
            usingParticipantsByIdentity;
    public Stack<Set<SourceTable>> stackHiddenSources;

    public boolean inTargetExpression;
    public int tempTableNum;
    public int currentOrdinal = -1;
    public int uncertainOrdinal = -1;
    public int currentStarBudget = -1;
    public int scalarSubqueryDepth;
    public boolean learningOnly;
    public boolean metadataPass;
    public boolean learningDisabled;
    public Map<SQLTableSource, SourceNode> tableSourceBindings = new IdentityHashMap<>();
    public Map<SourceTable, SourceNode> currentExpandedSourceNodes = new HashMap<>();

    public Set<String> tempTables;
    public Set<String> usedTargets;
    public Stack<Map<String, String>> cteScopes;
    public Stack<Set<String>> collectCteScopes;
    public Stack<Boolean> lateralScopes;
    public Stack<Map<String, List<String>>> targetColumnAliases;
    public Map<String, List<String>> cteDeclaredColumns;
    public Map<String, List<String>> cteDeclaredKeys;
    public Map<String, List<String>> targetOutputs;
    public Map<String, List<String>> targetOutputKeys;
    public Set<String> intermediateTargets;
    public Set<String> unionTargets;
    public Map<String, Boolean> targetWidthKnown;
    public Map<String, Boolean> targetNamesKnown;
    public Map<String, List<SourceTable>> targetOutputUnknownGroup;
    public Map<String, Boolean> targetWildcardContribution;
    public Map<String, List<WildcardSpan>> targetWildcardSpans;
    public Map<String, List<Boolean>> targetOutputNameKnown;
    public Map<String, Integer> targetMinWidth;
    public Map<String, Integer> targetWidthOverride;
    public Map<String, Integer> nonAuthoritativeDepth;
    public int lastQueryBlockWidth;
    public boolean lastQueryBlockWidthKnown;
    public boolean lastQueryBlockNamesKnown;
    public int lastQueryBlockMinWidth;
    public Stack<List<StarOutput>> stackStarPlan;
    public Stack<SourceNode> stackSourceNode;

    public void reset() {
        tempTables = new HashSet<>();
        usedTargets = new HashSet<>();
        cteScopes = new Stack<>();
        collectCteScopes = new Stack<>();
        lateralScopes = new Stack<>();
        targetColumnAliases = new Stack<>();
        cteDeclaredColumns = new HashMap<>();
        cteDeclaredKeys = new HashMap<>();
        targetOutputs = new HashMap<>();
        targetOutputKeys = new HashMap<>();
        intermediateTargets = new HashSet<>();
        unionTargets = new HashSet<>();
        targetWidthKnown = new HashMap<>();
        targetNamesKnown = new HashMap<>();
        targetOutputUnknownGroup = new HashMap<>();
        targetWildcardContribution = new HashMap<>();
        targetWildcardSpans = new HashMap<>();
        targetOutputNameKnown = new HashMap<>();
        targetMinWidth = new HashMap<>();
        targetWidthOverride = new HashMap<>();
        nonAuthoritativeDepth = new HashMap<>();
        lastQueryBlockWidth = 0;
        lastQueryBlockWidthKnown = true;
        lastQueryBlockNamesKnown = true;
        lastQueryBlockMinWidth = 0;
        stackStarPlan = new Stack<>();
        stackSourceNode = new Stack<>();
        stackSourceTable = new Stack<>();
        stackTargetTable = new Stack<>();
        stackTargetColumn = new Stack<>();
        stackSources = new Stack<>();
        stackUsing = new Stack<>();
        stackJoinBindings = new Stack<>();
        usingParticipantsByIdentity = new IdentityHashMap<>();
        tableSourceBindings = new IdentityHashMap<>();
        stackHiddenSources = new Stack<>();
        inTargetExpression = false;
        currentOrdinal = -1;
        uncertainOrdinal = -1;
        currentStarBudget = -1;
    }
}
