package com.laotie.model;

/**
 * How a target column is derived from a source column.
 *
 * <p>
 * The value refines the coarse {@link Instruction.OperationType}: while
 * {@code COLUMN_MAPPING} only says "one column feeds another", the relation says
 * whether the source is copied as-is, folded into an arithmetic/boolean expression,
 * passed through a function, aggregated, cast, and so on.
 * </p>
 */
public enum RelationType {
    /** Not classified (table-level mappings and other non-column instructions). */
    NONE,
    /** A plain field copy: {@code SELECT a AS b}, {@code INSERT ... SELECT a}, wildcard expansion. */
    COLUMN_COPY,
    /** The source participates in an arithmetic, comparison or boolean expression. */
    EXPRESSION,
    /** The source is an argument of a scalar function call. */
    FUNCTION,
    /** The source is an argument of an aggregate function such as SUM or COUNT. */
    AGGREGATE,
    /** The source is converted with CAST or the {@code ::} operator. */
    CAST,
    /** The source appears inside a CASE expression. */
    CASE,
    /** The source is read by a scalar subquery. */
    SUBQUERY,
    /** A table-level or positional wildcard injection. */
    WILDCARD,
    /** A literal or other source-less expression. */
    CONSTANT
}
