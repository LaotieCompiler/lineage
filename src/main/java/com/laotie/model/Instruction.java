package com.laotie.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.metadata.Column;
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

    @Override
    public String toString() {
        if (operation == OperationType.TABLE_MAPPING) {
            return String.format("%s.* -> %s.*", from, to);
        } else if (operation == OperationType.COLUMNS_INJECT) {
            return String.format("%s.* -> %s", from, to);
        }
        return String.format("%s -> %s", from, to);
    }

    public static List<Instruction> toColumnInstructions(List<Instruction> sourceInstructions, MetaBinder metaBinder){
        if (metaBinder == null ){
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
                
                // parse table name and column list from TB(cola,colb) pattern
                if (to.indexOf("(")>0 && to.endsWith(")")) {
                    String[] colsStr = to.substring(to.indexOf("(")+1, to.length() - 1).split(",");
                    toTable = to.substring(0, to.indexOf("(")); 
                    toCols = new ArrayList<>();
                    for (String colStr : colsStr) {
                        toCols.add(new Column(colStr.trim()));
                    }
                }

                if (toCols == null || toCols.isEmpty()){
                    toCols = new ArrayList<>();
                    toCols.addAll(fromCols);
                }

                try{
                    if (!metaBinder.tableInclude(new Table(toTable), toCols)){
                        LOGGER.log( Level.WARNING, String.format("Table %s not matched colmuns", toTable));
                        metaBinder.appendColumns(new Table(toTable), toCols);
                    }
                }catch(MetadataNotFoundException e){
                    metaBinder.setColumns(new Table(toTable), toCols);
                }

                if (fromCols.size() != toCols.size()){
                    throw new RuntimeException(String.format("Column size not matched, from %s, to %s", fromCols.size(), toCols.size()));
                }

                for (int i = 0; i < fromCols.size(); i++){
                    Column fromCol = fromCols.get(i);
                    Column toCol = toCols.get(i);
                    colInstructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            String.format("%s.%s", instruction.getFrom(), fromCol.getColumnName()),
                            String.format("%s.%s", toTable, toCol.getColumnName()))
                    );
                }
            } else if (instruction.getOperation() == OperationType.TABLE_MAPPING){
                metaBinder.setColumns( new Table(instruction.getTo()), metaBinder.getColumns(new Table(instruction.getFrom())));
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