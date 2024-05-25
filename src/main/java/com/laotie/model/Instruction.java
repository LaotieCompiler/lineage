package com.laotie.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Instruction {

    OperationType operation;

    String from;
    String to;

    public enum OperationType {
        COLUMN_MAPPING, // select tb.col as A
        COLUMNS_INJECT, // select tb.*
        TABLE_MAPPING, // from tb as A
        EXPRESSION_TRANSFORMATION
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
}