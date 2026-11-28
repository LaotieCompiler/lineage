package com.laotie.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.laotie.exception.MetadataNotFoundException;
import com.laotie.identifier.Identifiers;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.Table;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Instruction {
    private static final Logger LOGGER = Logger.getLogger(Instruction.class.getName());

    /** A column whose name is unknown but whose position is known. */
    private static final Column UNNAMED = new Column(null, "\u0000unnamed");



    OperationType operation;

    String from;
    String to;

    /**
     * How {@link #from} feeds {@link #to}. Defaults to a coarse value derived from
     * {@link #operation} and is refined by the analyzer for real expressions.
     */
    RelationType relation = RelationType.NONE;

    /**
     * The full source expression this edge belongs to, for example
     * {@code concat(a, '-', b)}. Null when the edge does not come from a projection.
     */
    String expression;

    /**
     * The nearest enclosing function name for a {@link RelationType#FUNCTION} or
     * {@link RelationType#AGGREGATE} edge, for example {@code UPPER}.
     */
    String functionName;

    /**
     * The nearest enclosing operator for an {@link RelationType#EXPRESSION} edge,
     * for example {@code +}, {@code =} or {@code BETWEEN}.
     */
    String operator;

    /**
     * Zero-based start position of this projection in the query's output. -1 when not a
     * projection (for example table mappings or update assignments).
     */
    int ordinal = -1;
    boolean fromTemporary;
    boolean toTemporary;

    /**
     * For a wildcard whose {@link #to} carries an explicit column list, the table the
     * edge really points at. Null when {@link #to} is already a table identity.
     */
    String wildcardTable;

    /**
     * True when the wildcard occupies a position count that is already known (for
     * example from an INSERT target list), so later positions stay deterministic.
     */
    boolean wildcardWidthKnown;

    /**
     * True when the projection's column positions are unknown, so the wildcard must
     * stay a table-level edge even if the source columns themselves are known.
     */
    boolean positionalWildcard;

    /**
     * The explicit target column list of an INSERT-style wildcard, if any. Kept
     * separately so a table-level edge never covers unwritten columns.
     */
    String populatedColumns;

    /**
     * The number of output positions a width-known wildcard occupies, or -1.
     */
    int wildcardWidth = -1;

    public enum OperationType {
        COLUMN_MAPPING, // select tb.col as A
        EXPR_MAPPING, // select tb.col as A
        COLUMNS_INJECT, // select tb.*
        TABLE_MAPPING, // from tb as A
    }

    public Instruction(OperationType operation, String from, String to) {
        this.operation = operation;
        this.from = from;
        this.to = to;
        this.relation = defaultRelation(operation);
    }

    public Instruction(OperationType operation, String from, String to, int ordinal) {
        this.operation = operation;
        this.from = from;
        this.to = to;
        this.ordinal = ordinal;
        this.relation = defaultRelation(operation);
    }

    public Instruction(OperationType operation, String from, String to, int ordinal,
            boolean fromTemporary, boolean toTemporary) {
        this.operation = operation;
        this.from = from;
        this.to = to;
        this.ordinal = ordinal;
        this.fromTemporary = fromTemporary;
        this.toTemporary = toTemporary;
        this.relation = defaultRelation(operation);
    }

    /**
     * The relation implied by the instruction kind when the analyzer has not refined
     * it yet.
     */
    private static RelationType defaultRelation(OperationType operation) {
        if (operation == null) {
            return RelationType.NONE;
        }
        switch (operation) {
            case COLUMN_MAPPING:
                return RelationType.COLUMN_COPY;
            case EXPR_MAPPING:
                return RelationType.CONSTANT;
            case COLUMNS_INJECT:
                return RelationType.WILDCARD;
            default:
                return RelationType.NONE;
        }
    }

    /**
     * Carry the relation detail of this instruction over to a rewritten copy.
     */
    void copyRelationTo(Instruction copy) {
        copy.setRelation(relation);
        copy.setExpression(expression);
        copy.setFunctionName(functionName);
        copy.setOperator(operator);
    }

    @Override
    public String toString() {
        if (operation == OperationType.TABLE_MAPPING) {
            return String.format("%s.* -> %s.*", from, to);
        } else if (operation == OperationType.COLUMNS_INJECT) {
            return String.format("%s.* -> %s", from, to);
        }
        return String.format("%s -> %s", from, to);
    }

    /**
     * Metadata-aware expansion of wildcard instructions.
     */
    public static List<Instruction> toColumnInstructions(List<Instruction> sourceInstructions,
            MetadataContext context) {
        return toColumnInstructions(sourceInstructions, context, new HashMap<>());
    }

    public static List<Instruction> toColumnInstructions(List<Instruction> sourceInstructions,
            MetadataContext context, Map<String, List<String>> targetOutputs) {
        if (context == null) {
            return new ArrayList<>(sourceInstructions);
        }

        List<Instruction> result = new ArrayList<>();
        Map<String, List<Column>> tempColumns = new HashMap<>();
        Map<String, Set<Integer>> tempUnknown = new HashMap<>();
        Map<String, Set<Integer>> tempWildcardKnown = new HashMap<>();

        for (Instruction instruction : sourceInstructions) {
            switch (instruction.getOperation()) {
                case COLUMNS_INJECT:
                    expandColumnsInject(instruction, context, targetOutputs, tempColumns,
                            tempUnknown, tempWildcardKnown, result);
                    break;
                case COLUMN_MAPPING:
                case EXPR_MAPPING:
                    Instruction rewritten = rewriteTempTarget(instruction, tempColumns);
                    result.add(rewritten);
                    registerTempTarget(rewritten.getTo(), rewritten.getOrdinal(),
                            rewritten.isToTemporary(), tempColumns);
                    break;
                default:
                    result.add(instruction);
                    break;
            }
        }
        return result;
    }

    private static Instruction rewriteTempTarget(Instruction instruction,
            Map<String, List<Column>> tempColumns) {
        if (instruction.getOrdinal() < 0 || !instruction.isToTemporary()) {
            return instruction;
        }
        String target = instruction.getTo();
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(target);
        if (parts.length < 2) {
            return instruction;
        }
        String table = joinTableParts(parts);
        String column = MetadataCatalog.normalizeIdentifier(parts[parts.length - 1]);
        List<Column> existing = tempColumns.get(table);
        boolean nameCollision = false;
        boolean positionOccupied = false;
        if (existing != null) {
            for (int i = 0; i < existing.size(); i++) {
                Column tempColumn = existing.get(i);
                if (tempColumn == null) {
                    continue;
                }
                if (i != instruction.getOrdinal()
                        && tempColumn.getColumnName().equals(column)) {
                    nameCollision = true;
                }
                if (i == instruction.getOrdinal()
                        && !tempColumn.getColumnName().equals(column)) {
                    positionOccupied = true;
                }
            }
        }
        if (!nameCollision && !positionOccupied) {
            return instruction;
        }
        String positional = "col" + (instruction.getOrdinal() + 1);
        Instruction rewritten = new Instruction(instruction.getOperation(),
                instruction.getFrom(), table + "." + positional,
                instruction.getOrdinal(), instruction.isFromTemporary(), true);
        instruction.copyRelationTo(rewritten);
        return rewritten;
    }

    private static void expandColumnsInject(Instruction instruction, MetadataContext context,
            Map<String, List<String>> targetOutputs, Map<String, List<Column>> tempColumns,
            Map<String, Set<Integer>> tempUnknown,
            Map<String, Set<Integer>> tempWildcardKnown, List<Instruction> result) {
        String fromTable = instruction.getFrom();
        String toTable = instruction.getTo();

        if (instruction.isPositionalWildcard()) {
            String target = toTable;
            int lparen = findColumnListStart(target);
            if (lparen > 0) {
                target = target.substring(0, lparen);
            }
            Instruction kept = new Instruction(OperationType.COLUMNS_INJECT, fromTable,
                    target, instruction.getOrdinal(), instruction.isFromTemporary(),
                    instruction.isToTemporary());
            kept.setPopulatedColumns(instruction.getPopulatedColumns());
            kept.setWildcardWidthKnown(instruction.isWildcardWidthKnown());
            kept.setWildcardWidth(instruction.getWildcardWidth());
            kept.setPositionalWildcard(instruction.isPositionalWildcard());
            instruction.copyRelationTo(kept);
            result.add(kept);
            if (instruction.isToTemporary()) {
                int at = Math.max(instruction.getOrdinal(), 0);
                if (instruction.isWildcardWidthKnown()) {
                    tempWildcardKnown.computeIfAbsent(target,
                            key -> new HashSet<Integer>()).add(at);
                } else {
                    markUnknownTempColumn(tempColumns, target, instruction.getOrdinal());
                    tempUnknown.computeIfAbsent(target,
                            key -> new HashSet<Integer>()).add(at);
                }
            }
            return;
        }

        List<Column> fromColumns = instruction.isFromTemporary()
                ? tempColumns.get(fromTable)
                : physicalColumns(fromTable, context);
        boolean partiallyKnown = false;
        if (fromColumns != null) {
            for (Column column : fromColumns) {
                if (column == null) {
                    partiallyKnown = true;
                    break;
                }
            }
        }
        Set<Integer> unknownPositions = instruction.isFromTemporary()
                ? tempUnknown.get(fromTable) : null;
        Set<Integer> knownWidthWildcards = instruction.isFromTemporary()
                ? tempWildcardKnown.get(fromTable) : null;
        if (partiallyKnown || unknownPositions != null && !unknownPositions.isEmpty()
                || knownWidthWildcards != null && !knownWidthWildcards.isEmpty()) {
            // The source still has unexpanded wildcard positions: keep a table-level edge
            // even when some columns are known.
            keepWildcardEdge(instruction, fromTable, tempColumns, tempUnknown,
                    tempWildcardKnown, result);
        }
        if (fromColumns == null || fromColumns.isEmpty()) {
            if (instruction.getWildcardTable() == null && !partiallyKnown
                    && (unknownPositions == null || unknownPositions.isEmpty())) {
                result.add(instruction);
            }
            if (instruction.isToTemporary()) {
                int at = Math.max(instruction.getOrdinal(), 0);
                if (instruction.isWildcardWidthKnown()) {
                    // Position known, name unknown: remember it separately so a known
                    // column from another UNION branch can still be mapped.
                    tempWildcardKnown.computeIfAbsent(toTable,
                            key -> new HashSet<Integer>()).add(at);
                    List<Column> columns = tempColumns.computeIfAbsent(toTable,
                            key -> new ArrayList<Column>());
                    while (columns.size() <= at) {
                        columns.add(null);
                    }
                    if (columns.get(at) == null) {
                        columns.set(at, UNNAMED);
                    }
                } else {
                    markUnknownTempColumn(tempColumns, toTable, instruction.getOrdinal());
                    tempUnknown.computeIfAbsent(toTable,
                            key -> new HashSet<Integer>()).add(at);
                }
            }
            return;
        }

        String targetTable;
        List<String> explicitTargets = new ArrayList<>();
        int lparen = findColumnListStart(toTable);
        if (lparen > 0) {
            targetTable = toTable.substring(0, lparen);
            String columns = toTable.substring(lparen + 1, toTable.length() - 1);
            for (String column : splitColumnList(columns)) {
                explicitTargets.add(column.trim());
            }
        } else {
            targetTable = toTable;
        }

        if (instruction.isToTemporary() && explicitTargets.isEmpty()
                && instruction.getOrdinal() >= 0) {
            List<String> named = targetOutputs.get(targetTable);
            int start = instruction.getOrdinal();
            boolean namesComplete = named == null
                    || start + fromColumns.size() <= named.size();
            if (namesComplete && named != null) {
                for (int i = 0; i < fromColumns.size(); i++) {
                    if (named.get(start + i) == null) {
                        namesComplete = false;
                        break;
                    }
                }
            }
            if (!namesComplete) {
                // The projection's target names are not all known: keep the wildcard
                // rather than inventing positional names.
                Instruction kept = new Instruction(OperationType.COLUMNS_INJECT, fromTable,
                        targetTable, instruction.getOrdinal(),
                        instruction.isFromTemporary(), instruction.isToTemporary());
                instruction.copyRelationTo(kept);
                result.add(kept);
                markUnknownTempColumn(tempColumns, targetTable, instruction.getOrdinal());
                tempUnknown.computeIfAbsent(targetTable,
                        key -> new HashSet<Integer>()).add(Math.max(start, 0));
                return;
            }
        }

        List<String> targets;
        if (!explicitTargets.isEmpty()) {
            Table physical = physicalTable(targetTable, context);
            targets = new ArrayList<>();
            for (String explicitTarget : explicitTargets) {
                String target = MetadataCatalog.normalizeIdentifier(explicitTarget);
                if (physical != null) {
                    String resolved = MetadataCatalog.resolveColumnName(physical, explicitTarget,
                            context.getCatalog().getDialect());
                    if (resolved != null) {
                        target = resolved;
                    }
                }
                targets.add(target);
            }
        } else if (instruction.isToTemporary()) {
            int start = instruction.getOrdinal() >= 0
                    ? instruction.getOrdinal()
                    : tempColumns.containsKey(targetTable) ? tempColumns.get(targetTable).size() : 0;
            List<String> named = targetOutputs.get(targetTable);
            targets = new ArrayList<>();
            for (int i = 0; i < fromColumns.size(); i++) {
                String name = named != null && start + i < named.size()
                        ? named.get(start + i) : "col" + (start + i + 1);
                targets.add(name);
            }
            putTempColumns(tempColumns, targetTable, start, targets);
        } else {
            List<Column> physical = physicalColumns(targetTable, context);
            if (physical != null && physical.size() >= fromColumns.size()) {
                targets = new ArrayList<>();
                for (int i = 0; i < fromColumns.size(); i++) {
                    targets.add(physical.get(i).getColumnName());
                }
            } else {
                targets = new ArrayList<>();
                for (int i = 0; i < fromColumns.size(); i++) {
                    Column column = fromColumns.get(i);
                    targets.add(column == null ? "col" + (i + 1)
                            : column.getColumnName());
                }
            }
        }

        if (instruction.isToTemporary() && !explicitTargets.isEmpty()) {
            int start = instruction.getOrdinal() >= 0
                    ? instruction.getOrdinal()
                    : tempColumns.containsKey(targetTable)
                            ? tempColumns.get(targetTable).size() : 0;
            putTempColumns(tempColumns, targetTable, start, targets);
        }

        int firstUnknown = -1;
        for (int i = 0; i < fromColumns.size(); i++) {
            if (fromColumns.get(i) == null
                    || unknownPositions != null && unknownPositions.contains(i)) {
                firstUnknown = i;
                break;
            }
        }
        int size = Math.min(fromColumns.size(), targets.size());
        for (int i = 0; i < size; i++) {
            Column from = fromColumns.get(i);
            if (from == null || from == UNNAMED
                    || firstUnknown >= 0 && i > firstUnknown) {
                // This position is (or follows) an unresolved wildcard whose width is
                // unknown, so a column-level edge would be a guess.
                continue;
            }
            Instruction mapping = new Instruction(OperationType.COLUMN_MAPPING,
                    fromTable + "." + renderIdentifier(from.getColumnName()),
                    targetTable + "." + renderIdentifier(targets.get(i)), -1,
                    instruction.isFromTemporary(), instruction.isToTemporary());
            if (instruction.getRelation() == RelationType.SUBQUERY) {
                // A table-level claim of a scalar subquery stays SUBQUERY when it is
                // narrowed to individual columns.
                mapping.setRelation(RelationType.SUBQUERY);
                mapping.setExpression(instruction.getExpression());
            }
            result.add(mapping);
        }
    }

    private static Table physicalTable(String table, MetadataContext context) {
        try {
            return context.getCatalog().findTableByDisplayName(table).getTable();
        } catch (MetadataNotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Column> physicalColumns(String table, MetadataContext context) {
        Table metadataTable = physicalTable(table, context);
        return metadataTable == null ? null : metadataTable.getFields();
    }

    private static int findColumnListStart(String value) {
        if (value == null || !value.endsWith(")")) {
            return -1;
        }
        char quote = 0;
        int depth = 0;
        int candidate = -1;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '[') {
                quote = ']';
            } else if (ch == '(') {
                if (depth == 0) {
                    candidate = i;
                }
                depth++;
            } else if (ch == ')') {
                depth--;
            }
        }
        return depth == 0 ? candidate : -1;
    }

    private static void markUnknownTempColumn(Map<String, List<Column>> tempColumns,
            String table, int ordinal) {
        List<Column> columns = tempColumns.computeIfAbsent(table,
                key -> new ArrayList<Column>());
        int at = ordinal >= 0 ? ordinal : columns.size();
        while (columns.size() <= at) {
            columns.add(null);
        }
    }

    private static void keepWildcardEdge(Instruction instruction, String fromTable,
            Map<String, List<Column>> tempColumns, Map<String, Set<Integer>> tempUnknown,
            Map<String, Set<Integer>> tempWildcardKnown, List<Instruction> result) {
        String target = instruction.getWildcardTable() != null
                ? instruction.getWildcardTable()
                : instruction.getTo();
        int lparen = findColumnListStart(target);
        if (lparen > 0) {
            target = target.substring(0, lparen);
        }
        Instruction edge = new Instruction(OperationType.COLUMNS_INJECT, fromTable, target,
                instruction.getOrdinal(), instruction.isFromTemporary(),
                instruction.isToTemporary());
        edge.setWildcardTable(target);
        edge.setPopulatedColumns(instruction.getPopulatedColumns());
        edge.setWildcardWidthKnown(instruction.isWildcardWidthKnown());
        edge.setWildcardWidth(instruction.getWildcardWidth());
        instruction.copyRelationTo(edge);
        result.add(edge);
        if (instruction.isToTemporary()) {
            int at = Math.max(instruction.getOrdinal(), 0);
            if (instruction.isWildcardWidthKnown()) {
                tempWildcardKnown.computeIfAbsent(target,
                        key -> new HashSet<Integer>()).add(at);
            } else {
                markUnknownTempColumn(tempColumns, target, instruction.getOrdinal());
                tempUnknown.computeIfAbsent(target, key -> new HashSet<Integer>())
                        .add(at);
            }
        }
    }

    private static void registerTempTarget(String target, int ordinal, boolean toTemporary,
            Map<String, List<Column>> tempColumns) {
        if (!toTemporary) {
            return;
        }
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(target);
        if (parts.length < 2) {
            return;
        }
        String table = joinTableParts(parts);
        String column = MetadataCatalog.normalizeIdentifier(parts[parts.length - 1]);
        putTempColumn(tempColumns, table, ordinal >= 0 ? ordinal : -1, column);
    }

    private static List<String> splitColumnList(String columns) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < columns.length(); i++) {
            char ch = columns.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    if (i + 1 < columns.length()
                            && columns.charAt(i + 1) == quote) {
                        current.append(columns.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
            } else if (ch == ',') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        result.add(current.toString());
        return result;
    }

    private static String joinTableParts(String[] parts) {
        StringBuilder table = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) {
                table.append('.');
            }
            table.append(parts[i]);
        }
        return table.toString();
    }

    private static String renderIdentifier(String name) {
        return Identifiers.render(name);
    }

    private static void putTempColumns(Map<String, List<Column>> tempColumns, String table,
            int start, List<String> names) {
        List<Column> columns = tempColumns.computeIfAbsent(table, key -> new ArrayList<>());
        for (int i = 0; i < names.size(); i++) {
            int position = start + i;
            while (columns.size() <= position) {
                columns.add(null);
            }
            columns.set(position, new Column(table, names.get(i)));
        }
    }

    private static void putTempColumn(Map<String, List<Column>> tempColumns, String table,
            int position, String name) {
        List<Column> columns = tempColumns.computeIfAbsent(table, key -> new ArrayList<>());
        int target = position >= 0 ? position : columns.size();
        while (columns.size() <= target) {
            columns.add(null);
        }
        columns.set(target, new Column(table, name));
    }

}
