package com.laotie.model;

public class Instruction {
    
    OperationType operation;
    

    public enum OperationType {
        COLUMN_MAPPING,
        TABLE_MAPPING,
        EXPRESSION_TRANSFORMATION
    }
    
}