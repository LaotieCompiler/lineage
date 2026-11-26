package com.laotie.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.laotie.exception.MetadataNotFoundException;
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

    OperationType operation;

    String from;
    String to;

    /**
     * Zero-based start position of this projection in the query's output. -1 when not a
     * projection (for example table mappings or update assignments).
     */
    int ordinal = -1;
    boolean fromTemporary;
    boolean toTemporary;

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
    }

    public Instruction(OperationType operation, String from, String to, int ordinal) {
        this.operation = operation;
        this.from = from;
        this.to = to;
        this.ordinal = ordinal;
    }

    public Instruction(OperationType operation, String from, String to, int ordinal,
            boolean fromTemporary, boolean toTemporary) {
        this.operation = operation;
        this.from = from;
        this.to = to;
        this.ordinal = ordinal;
        this.fromTemporary = fromTemporary;
        this.toTemporary = toTemporary;
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

        for (Instruction instruction : sourceInstructions) {
            switch (instruction.getOperation()) {
                case COLUMNS_INJECT:
                    expandColumnsInject(instruction, context, targetOutputs, tempColumns, result);
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
        return new Instruction(instruction.getOperation(), instruction.getFrom(),
                table + "." + positional, instruction.getOrdinal(),
                instruction.isFromTemporary(), true);
    }

    private static void expandColumnsInject(Instruction instruction, MetadataContext context,
            Map<String, List<String>> targetOutputs, Map<String, List<Column>> tempColumns,
            List<Instruction> result) {
        String fromTable = instruction.getFrom();
        String toTable = instruction.getTo();

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
        if (fromColumns == null || fromColumns.isEmpty() || partiallyKnown) {
            // The source columns are unknown: keep the wildcard so that the table-level
            // edge survives instead of being dropped. An explicit target column list is
            // not a table identity, so the edge points at the target table.
            int lparen = findColumnListStart(toTable);
            if (lparen > 0) {
                result.add(new Instruction(OperationType.COLUMNS_INJECT, fromTable,
                        toTable.substring(0, lparen), instruction.getOrdinal(),
                        instruction.isFromTemporary(), instruction.isToTemporary()));
            } else {
                result.add(instruction);
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
                for (Column column : fromColumns) {
                    targets.add(column.getColumnName());
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

        int size = Math.min(fromColumns.size(), targets.size());
        for (int i = 0; i < size; i++) {
            result.add(new Instruction(OperationType.COLUMN_MAPPING,
                    fromTable + "." + renderIdentifier(fromColumns.get(i).getColumnName()),
                    targetTable + "." + renderIdentifier(targets.get(i)), -1,
                    instruction.isFromTemporary(), instruction.isToTemporary()));
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
                    quote = 0;
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
        if (name == null || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
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

    public static List<Instruction> toColumnInstructions(List<Instruction> sourceInstructions, MetaBinder metaBinder) {
        if (metaBinder == null) {
            return sourceInstructions;
        }
        List<Instruction> colInstructions = new ArrayList<>();
        for (Instruction instruction : sourceInstructions) {
            if (instruction.getOperation() == OperationType.COLUMNS_INJECT) {
                String to = instruction.getTo();
                String fromTable = instruction.getFrom();
                String toTable = instruction.getTo();
                List<Column> fromCols = metaBinder.getColumns(new Table(fromTable));
                List<Column> toCols = metaBinder.getColumns(new Table(toTable));

                if (to.indexOf("(") > 0 && to.endsWith(")")) {
                    String[] colsStr = to.substring(to.indexOf("(") + 1, to.length() - 1).split(",");
                    toTable = to.substring(0, to.indexOf("("));
                    toCols = new ArrayList<>();
                    for (String colStr : colsStr) {
                        toCols.add(new Column(colStr.trim()));
                    }
                }

                if (toCols == null || toCols.isEmpty()) {
                    toCols = new ArrayList<>();
                    toCols.addAll(fromCols);
                }

                try {
                    if (!metaBinder.tableInclude(new Table(toTable), toCols)) {
                        LOGGER.log(Level.WARNING, String.format("Table %s not matched colmuns", toTable));
                        metaBinder.appendColumns(new Table(toTable), toCols);
                    }
                } catch (MetadataNotFoundException e) {
                    metaBinder.setColumns(new Table(toTable), toCols);
                }

                if (fromCols.size() != toCols.size()) {
                    throw new RuntimeException(String.format("Column size not matched, from %s, to %s", fromCols.size(), toCols.size()));
                }

                for (int i = 0; i < fromCols.size(); i++) {
                    Column fromCol = fromCols.get(i);
                    Column toCol = toCols.get(i);
                    colInstructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            String.format("%s.%s", instruction.getFrom(), fromCol.getColumnName()),
                            String.format("%s.%s", toTable, toCol.getColumnName())));
                }
            } else if (instruction.getOperation() == OperationType.TABLE_MAPPING) {
                metaBinder.setColumns(new Table(instruction.getTo()), metaBinder.getColumns(new Table(instruction.getFrom())));
                colInstructions.add(instruction);
            } else if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                metaBinder.columns2Table(Arrays.asList(new Column(instruction.getTo())));
                colInstructions.add(instruction);
            } else if (instruction.getOperation() == OperationType.EXPR_MAPPING) {
                metaBinder.columns2Table(Arrays.asList(new Column(instruction.getTo())));
                colInstructions.add(instruction);
            }
        }
        return colInstructions;
    }
}
