package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.analysis.RelationClassifier;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageGraph;
import com.laotie.model.LineageResult;
import com.laotie.model.RelationHop;
import com.laotie.model.RelationType;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * The column relation refinement: a lineage edge says whether the source is copied,
 * used in an expression, passed through a function, aggregated, and so on.
 */
public class RelationTypeLineageTest {

    private static List<Instruction> instructions(String sql) {
        return new DmlLineage().parse(sql).getInstructions();
    }

    private static Instruction only(List<Instruction> instructions) {
        assertEquals(1, instructions.size());
        return instructions.get(0);
    }

    private static Instruction from(List<Instruction> instructions, String source) {
        for (Instruction instruction : instructions) {
            if (source.equals(instruction.getFrom())) {
                return instruction;
            }
        }
        throw new AssertionError("no instruction from " + source + ": " + instructions);
    }

    @Test
    public void directCopyIsColumnCopy() {
        Instruction instruction = only(instructions("SELECT a AS b FROM t"));

        assertEquals(OperationType.COLUMN_MAPPING, instruction.getOperation());
        assertEquals(RelationType.COLUMN_COPY, instruction.getRelation());
        assertEquals("a", instruction.getExpression());
    }

    @Test
    public void arithmeticExpressionIsClassifiedWithItsOperator() {
        Instruction instruction = only(instructions("SELECT a + 1 AS b FROM t"));

        assertEquals(RelationType.EXPRESSION, instruction.getRelation());
        assertEquals("+", instruction.getOperator());
        assertEquals("a + 1", instruction.getExpression());
    }

    @Test
    public void scalarFunctionIsClassifiedWithItsName() {
        Instruction instruction = only(instructions("SELECT UPPER(a) AS b FROM t"));

        assertEquals(RelationType.FUNCTION, instruction.getRelation());
        assertEquals("UPPER", instruction.getFunctionName());
    }

    @Test
    public void aggregateFunctionIsClassifiedSeparately() {
        Instruction instruction = only(instructions("SELECT SUM(a) AS b FROM t"));

        assertEquals(RelationType.AGGREGATE, instruction.getRelation());
        assertEquals("SUM", instruction.getFunctionName());
    }

    @Test
    public void castIsClassified() {
        Instruction instruction = only(instructions("SELECT CAST(a AS INT) AS b FROM t"));

        assertEquals(RelationType.CAST, instruction.getRelation());
    }

    @Test
    public void caseMarksTheConditionAsExpressionAndBranchesAsCase() {
        List<Instruction> instructions = instructions(
                "SELECT CASE WHEN a > 0 THEN b ELSE c END AS d FROM t");

        Instruction condition = from(instructions, "t.a");
        assertEquals(RelationType.EXPRESSION, condition.getRelation());
        assertEquals(">", condition.getOperator());
        assertEquals(RelationType.CASE, from(instructions, "t.b").getRelation());
        assertEquals(RelationType.CASE, from(instructions, "t.c").getRelation());
    }

    @Test
    public void nestedCallKeepsTheNearestFunction() {
        Instruction instruction = only(instructions("SELECT SUM(UPPER(a)) AS b FROM t"));

        // The column enters UPPER first; the whole expression's aggregate is still
        // visible in the recorded expression text.
        assertEquals(RelationType.FUNCTION, instruction.getRelation());
        assertEquals("UPPER", instruction.getFunctionName());
        assertEquals("SUM(UPPER(a))", instruction.getExpression());
    }

    @Test
    public void literalProjectionIsConstant() {
        Instruction instruction = only(instructions("SELECT 1 AS b FROM t"));

        assertEquals(OperationType.EXPR_MAPPING, instruction.getOperation());
        assertEquals(RelationType.CONSTANT, instruction.getRelation());
    }

    @Test
    public void updateAssignmentUsesTheValueExpressionContext() {
        Instruction instruction = only(instructions("UPDATE t SET a = b + 1"));

        assertEquals(RelationType.EXPRESSION, instruction.getRelation());
        assertEquals("+", instruction.getOperator());
    }

    @Test
    public void graphPathsKeepTheRelationOfEveryHop() {
        MetadataContext context = LineageTestSupport.mysql(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.B (c1 INT);"
                        + "CREATE TABLE db1.C (c1 INT);");
        List<LineageResult> results = new DmlLineage(context).parseScript(
                "INSERT INTO db1.B(c1) SELECT UPPER(c1) FROM db1.A;"
                        + "INSERT INTO db1.C(c1) SELECT c1 FROM db1.B;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        Map<Column, List<List<RelationHop>>> paths =
                graph.relationPaths(new Column("db1.C.c1"));
        List<List<RelationHop>> alternatives = paths.get(new Column("db1.A.c1"));
        assertNotNull("A.c1 should be a source of C.c1", alternatives);
        List<RelationHop> path = alternatives.get(0);
        // C <- copy <- B <- copy <- temp <- copy <- temp <- UPPER <- A
        assertEquals(RelationType.COLUMN_COPY, path.get(0).getRelation());
        assertEquals(RelationType.FUNCTION,
                path.get(path.size() - 1).getRelation());
        for (int i = 1; i < path.size() - 1; i++) {
            assertEquals(RelationType.COLUMN_COPY, path.get(i).getRelation());
        }
        assertEquals("UPPER", path.get(path.size() - 1).getFunctionName());
    }

    @Test
    public void parallelUnionBranchesKeepBothRelations() {
        MetadataContext context = LineageTestSupport.mysql(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.B (c1 INT);");
        List<LineageResult> results = new DmlLineage(context).parseScript(
                "INSERT INTO db1.B(c1) "
                        + "SELECT c1 FROM db1.A UNION ALL SELECT UPPER(c1) FROM db1.A;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        List<List<RelationHop>> alternatives = graph.relationPaths(new Column("db1.B.c1"))
                .get(new Column("db1.A.c1"));
        assertNotNull(alternatives);
        boolean copy = false;
        boolean function = false;
        for (List<RelationHop> path : alternatives) {
            for (RelationHop hop : path) {
                copy |= hop.getRelation() == RelationType.COLUMN_COPY;
                function |= hop.getRelation() == RelationType.FUNCTION;
            }
        }
        assertTrue("the copy branch must stay visible", copy);
        assertTrue("the function branch must stay visible", function);
    }

    @Test
    public void compactKeepsWhitespaceInsideStringLiterals() {
        assertEquals("CONCAT(a, '  x  y  ') + 1",
                RelationClassifier.compact("CONCAT(a, '  x  y  ')\n+   1"));
    }

    @Test
    public void nestedNoFromScalarSubqueryKeepsBothOuterColumns() {
        MetadataContext context = LineageTestSupport.mysql(
                "CREATE TABLE db1.s (a INT, b INT);");
        LineageResult result = new DmlLineage(context).parse(
                "SELECT (SELECT COALESCE((SELECT s.b), s.a)) FROM db1.s");

        boolean sawA = false;
        boolean sawB = false;
        for (Instruction instruction : result.getInstructions()) {
            sawA |= instruction.getFrom() != null
                    && instruction.getFrom().endsWith(".a");
            sawB |= instruction.getFrom() != null
                    && instruction.getFrom().endsWith(".b");
        }
        assertTrue("s.a should reach the projection", sawA);
        assertTrue("the nested scalar subquery should keep s.b", sawB);
    }

    @Test
    public void unknownWidthScalarSubqueryKeepsSubqueryRelation() {
        ScanResult result = LineageScanner.withoutMetadata(DbType.mysql,
                LineageOptions.of(MetadataMode.OPTIONAL, false)).scan(
                "INSERT INTO t(c1) SELECT (SELECT * FROM missing);");

        List<List<RelationHop>> paths = result.getGraph()
                .relationPaths(new Column("t.c1")).get(new Column("missing.*"));
        assertNotNull(paths);
        assertEquals(RelationType.SUBQUERY, paths.get(0).get(0).getRelation());
    }

    @Test
    public void parallelWildcardAndSubqueryBranchesAreBothKept() {
        ScanResult result = LineageScanner.withoutMetadata(DbType.mysql,
                LineageOptions.of(MetadataMode.OPTIONAL, false)).scan(
                "INSERT INTO t(c1) SELECT * FROM missing "
                        + "UNION ALL SELECT (SELECT * FROM missing);");

        List<List<RelationHop>> paths = result.getGraph()
                .relationPaths(new Column("t.c1")).get(new Column("missing.*"));
        assertNotNull(paths);
        boolean wildcard = false;
        boolean subquery = false;
        for (List<RelationHop> path : paths) {
            for (RelationHop hop : path) {
                wildcard |= hop.getRelation() == RelationType.WILDCARD;
                subquery |= hop.getRelation() == RelationType.SUBQUERY;
            }
        }
        assertTrue("the wildcard branch must stay visible", wildcard);
        assertTrue("the scalar-subquery branch must stay visible", subquery);
    }

    @Test
    public void graphPathsForACopyStayCopies() {
        MetadataContext context = LineageTestSupport.mysql(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.B (c1 INT);");
        List<LineageResult> results = new DmlLineage(context).parseScript(
                "INSERT INTO db1.B(c1) SELECT c1 FROM db1.A;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        List<List<RelationHop>> alternatives = graph.relationPaths(new Column("db1.B.c1"))
                .get(new Column("db1.A.c1"));
        assertNotNull(alternatives);
        for (List<RelationHop> path : alternatives) {
            for (RelationHop hop : path) {
                assertEquals(RelationType.COLUMN_COPY, hop.getRelation());
            }
        }
    }
}
