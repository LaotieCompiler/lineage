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
        if (context == null) {
            return new ArrayList<>(sourceInstructions);
        }

        List<Instruction> result = new ArrayList<>();
        Map<String, List<Column>> tempColumns = new HashMap<>();

        for (Instruction instruction : sourceInstructions) {
            switch (instruction.getOperation()) {
                case COLUMNS_INJECT:
                    expandColumnsInject(instruction, context, tempColumns, result);
                    break;
                case COLUMN_MAPPING:
                case EXPR_MAPPING:
                    Instruction rewritten = rewriteTempTarget(instruction, tempColumns);
                    result.add(rewritten);
                    registerTempTarget(rewritten.getTo(), rewritten.getOrdinal(), tempColumns);
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
        if (instruction.getOrdinal() < 0) {
            return instruction;
        }
        String target = instruction.getTo();
        int idx = target.lastIndexOf('.');
        if (idx < 0) {
            return instruction;
        }
        String table = target.substring(0, idx);
        if (!isTempTable(table)) {
            return instruction;
        }
        String column = target.substring(idx + 1);
        List<Column> existing = tempColumns.get(table);
        boolean nameCollision = false;
        boolean positionOccupied = false;
        if (existing != null) {
            for (Column tempColumn : existing) {
                if (tempColumn != null
                        && tempColumn.getColumnName().equalsIgnoreCase(column)) {
                    nameCollision = true;
                }
            }
            positionOccupied = existing.size() > instruction.getOrdinal()
                    && existing.get(instruction.getOrdinal()) != null;
        }
        if (!nameCollision && !positionOccupied) {
            return instruction;
        }
        String positional = "col" + (instruction.getOrdinal() + 1);
        return new Instruction(instruction.getOperation(), instruction.getFrom(),
                table + "." + positional, instruction.getOrdinal());
    }

    private static void expandColumnsInject(Instruction instruction, MetadataContext context,
            Map<String, List<Column>> tempColumns, List<Instruction> result) {
        String fromTable = instruction.getFrom();
        String toTable = instruction.getTo();

        List<Column> fromColumns = columnsOf(fromTable, context, tempColumns);
        if (fromColumns == null || fromColumns.isEmpty()) {
            return;
        }

        String targetTable;
        List<String> explicitTargets = new ArrayList<>();
        int lparen = toTable.indexOf('(');
        if (lparen > 0 && toTable.endsWith(")")) {
            targetTable = toTable.substring(0, lparen);
            String columns = toTable.substring(lparen + 1, toTable.length() - 1);
            for (String column : columns.split(",")) {
                explicitTargets.add(column.trim());
            }
        } else {
            targetTable = toTable;
        }

        List<String> targets;
        if (!explicitTargets.isEmpty()) {
            targets = explicitTargets;
        } else if (isTempTable(targetTable)) {
            int start = instruction.getOrdinal() >= 0
                    ? instruction.getOrdinal()
                    : tempColumns.containsKey(targetTable) ? tempColumns.get(targetTable).size() : 0;
            targets = new ArrayList<>();
            for (int i = 0; i < fromColumns.size(); i++) {
                targets.add("col" + (start + i + 1));
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

        int size = Math.min(fromColumns.size(), targets.size());
        for (int i = 0; i < size; i++) {
            result.add(new Instruction(OperationType.COLUMN_MAPPING,
                    fromTable + "." + fromColumns.get(i).getColumnName(),
                    targetTable + "." + targets.get(i)));
        }
    }

    private static List<Column> columnsOf(String table, MetadataContext context,
            Map<String, List<Column>> tempColumns) {
        if (tempColumns.containsKey(table)) {
            return tempColumns.get(table);
        }
        if (isTempTable(table)) {
            return null;
        }
        return physicalColumns(table, context);
    }

    private static List<Column> physicalColumns(String table, MetadataContext context) {
        try {
            Table metadataTable = context.getCatalog().findTable(table).getTable();
            return metadataTable.getFields();
        } catch (MetadataNotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void registerTempTarget(String target, int ordinal,
            Map<String, List<Column>> tempColumns) {
        int idx = target.lastIndexOf('.');
        if (idx < 0) {
            return;
        }
        String table = target.substring(0, idx);
        if (!isTempTable(table)) {
            return;
        }
        String column = target.substring(idx + 1);
        putTempColumn(tempColumns, table, ordinal >= 0 ? ordinal : -1, column);
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

    private static int tempSize(Map<String, List<Column>> tempColumns, String table) {
        List<Column> columns = tempColumns.get(table);
        return columns == null ? 0 : columns.size();
    }

    private static boolean isTempTable(String table) {
        return table.matches("temp\\d+") || table.matches("stmt\\d+\\.temp\\d+");
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
