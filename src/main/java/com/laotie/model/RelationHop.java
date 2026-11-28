package com.laotie.model;

import java.util.Objects;

import lombok.Getter;

/**
 * One hop of a column lineage path: how the next column was derived from this one.
 */
@Getter
public final class RelationHop {

    private final RelationType relation;
    private final String expression;
    private final String functionName;
    private final String operator;
    /** True when hops closer to the source were omitted to bound the path length. */
    private final boolean truncated;

    public RelationHop(RelationType relation, String expression, String functionName,
            String operator) {
        this(relation, expression, functionName, operator, false);
    }

    public RelationHop(RelationType relation, String expression, String functionName,
            String operator, boolean truncated) {
        this.relation = relation == null ? RelationType.NONE : relation;
        this.expression = expression;
        this.functionName = functionName;
        this.operator = operator;
        this.truncated = truncated;
    }

    /** A copy of this hop marked as the cut point of an over-long path. */
    public RelationHop asTruncated() {
        return new RelationHop(relation, expression, functionName, operator, true);
    }

    @Override
    public String toString() {
        return relation.name();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelationHop)) {
            return false;
        }
        RelationHop hop = (RelationHop) other;
        return relation == hop.relation
                && truncated == hop.truncated
                && Objects.equals(expression, hop.expression)
                && Objects.equals(functionName, hop.functionName)
                && Objects.equals(operator, hop.operator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relation, expression, functionName, operator, truncated);
    }
}
