package com.laotie.model;

import org.jgrapht.graph.DefaultEdge;

import lombok.Getter;
import lombok.Setter;

/**
 * A lineage graph edge that remembers how the target column is derived from the
 * source column. The graph's generic type stays {@link DefaultEdge}, so existing
 * callers are unaffected.
 */
@Getter
@Setter
public class LineageEdge extends DefaultEdge {

    private static final long serialVersionUID = 1L;

    private RelationType relation = RelationType.NONE;
    private String expression;
    private String functionName;
    private String operator;

    /** Copy the relation detail of an instruction onto this edge. */
    public void label(Instruction instruction) {
        if (instruction == null) {
            return;
        }
        this.relation = instruction.getRelation() == null
                ? RelationType.NONE : instruction.getRelation();
        this.expression = instruction.getExpression();
        this.functionName = instruction.getFunctionName();
        this.operator = instruction.getOperator();
    }

    public void label(Instruction instruction, RelationType fallback) {
        label(instruction);
        if (relation == RelationType.NONE && fallback != null) {
            relation = fallback;
        }
    }
}
