package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;

/**
 * Specification for metadata-driven column resolution.
 *
 * <p>
 * Unqualified columns are resolved against the visible output columns of the query block.
 * A {@code JOIN ... USING} merged column is one visible column that may have lineage from
 * multiple source tables. Independent output columns that share a name are ambiguous:
 * multiple matches throw {@link AmbiguousColumnException}; no match throws
 * {@link ColumnNotFoundException}. Instructions use fully qualified metadata table names
 * (for example {@code db1.A.c1}), never the SQL-written short name.
 *
 * <p>
 * Expected to be red until {@code DmlLineage} consumes a {@link MetadataContext} and exposes
 * {@code parse(String)}.
 */
public class ColumnResolutionTest {

    @Test
    public void uniqueColumnOnTheRightTableIsResolved() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context).parse("select c2 from A,B");

        assertEquals(1, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db2.B.c2", "temp0.c2");
    }

    @Test
    public void columnInBothTablesIsAmbiguous() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c1 INT);");

        assertThrows(AmbiguousColumnException.class,
                () -> new DmlLineage(context).parse("select c1 from A,B"));
    }

    @Test
    public void missingColumnThrows() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        assertThrows(ColumnNotFoundException.class,
                () -> new DmlLineage(context).parse("select c3 from A,B"));
    }

    @Test
    public void aliasDisambiguationStillEmitsFullyQualifiedSourceName() {
        // c2 exists in both tables, so only the alias-qualified form is unambiguous.
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, c2 INT); CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context).parse("select b.c2 from A a, B b");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertInstruction(instructions.get(0), OperationType.TABLE_MAPPING, "db1.A", "a");
        assertInstruction(instructions.get(1), OperationType.TABLE_MAPPING, "db2.B", "b");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "db2.B.c2", "temp0.c2");

        assertThrows(AmbiguousColumnException.class,
                () -> new DmlLineage(context).parse("select c2 from A a, B b"));
    }

    @Test
    public void qualifiedOwnerMissingColumnThrows() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        // The owner b exists, but B has no c1.
        assertThrows(ColumnNotFoundException.class,
                () -> new DmlLineage(context).parse("select b.c1 from A a, B b"));
    }

    @Test
    public void unqualifiedColumnResolvesAcrossSchemas() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT); CREATE TABLE sch2.B (c2 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        LineageResult result = new DmlLineage(context).parse("select c1 from A,B");

        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "sch1.A.c1", "temp0.c1");
    }

    @Test
    public void unknownQualifiedOwnerThrows() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        assertThrows(MetadataNotFoundException.class,
                () -> new DmlLineage(context).parse("select X.c1 from A,B"));
    }

    @Test
    public void starFromMultipleTablesEmitsOneInjectionPerTable() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context).parse("select * from A,B");

        assertEquals(2, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMNS_INJECT, "db1.A", "temp0");
        assertInstruction(result.getInstructions().get(1),
                OperationType.COLUMNS_INJECT, "db2.B", "temp0");
    }

    @Test
    public void aliasStarUsesFullyQualifiedSourceName() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context).parse("select a.* from A a, B b");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertInstruction(instructions.get(0), OperationType.TABLE_MAPPING, "db1.A", "a");
        assertInstruction(instructions.get(1), OperationType.TABLE_MAPPING, "db2.B", "b");
        assertInstruction(instructions.get(2), OperationType.COLUMNS_INJECT, "db1.A", "temp0");
    }

    @Test
    public void insertSelectKeepsSelectListOrder() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c2 INT);"
                        + "CREATE TABLE db2.B (c1 INT);"
                        + "CREATE TABLE T (c1 INT, c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T(c1,c2) select c2, c1 from A,B");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "db1.A.c2", "temp1.c2");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "db2.B.c1", "temp1.c1");
        assertInstruction(instructions.get(2), OperationType.COLUMNS_INJECT, "temp1", "T(c1,c2)");
    }

    @Test
    public void wildcardExpansionUsesDeclaredColumnOrder() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, c2 INT);"
                        + "CREATE TABLE T (c1 INT, c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T(c1,c2) select * from A");

        List<Instruction> expanded = Instruction.toColumnInstructions(
                result.getInstructions(), context);

        // Star columns are appended to the temp table by position: col1, col2, ...
        assertEquals(4, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "temp1.col1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING, "db1.A.c2", "temp1.col2");
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING, "temp1.col1", "T.c1");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING, "temp1.col2", "T.c2");
    }

    @Test
    public void insertWithoutColumnListExpandsStarUsingDeclaredOrder() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, c2 INT);"
                        + "CREATE TABLE T (cola INT, colb INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T select * from A");

        List<Instruction> expanded = Instruction.toColumnInstructions(
                result.getInstructions(), context);

        assertEquals(4, expanded.size());
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING, "temp1.col1", "T.cola");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING, "temp1.col2", "T.colb");
    }

    @Test
    public void wildcardExpansionHonorsReorderedTargetColumns() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, c2 INT);"
                        + "CREATE TABLE T (c1 INT, c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T(c2,c1) select * from A");

        List<Instruction> expanded = Instruction.toColumnInstructions(
                result.getInstructions(), context);

        assertEquals(4, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "temp1.col1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING, "db1.A.c2", "temp1.col2");
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING, "temp1.col1", "T.c2");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING, "temp1.col2", "T.c1");
    }

    @Test
    public void consecutiveStarProjectionsKeepPositionalOrder() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db2.B (c1 INT);"
                        + "CREATE TABLE T (x INT, y INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T(x,y) select A.*, B.* from A, B");

        List<Instruction> expanded = Instruction.toColumnInstructions(
                result.getInstructions(), context);

        assertEquals(4, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "temp1.col1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING, "db2.B.c1", "temp1.col2");
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING, "temp1.col1", "T.x");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING, "temp1.col2", "T.y");
    }

    @Test
    public void expressionProjectionSharesOneOrdinalForAllSources() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select A.c1 + B.c2 as s, B.c2 from A,B");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertEquals(0, instructions.get(0).getOrdinal());
        assertEquals(0, instructions.get(1).getOrdinal());
        assertEquals(1, instructions.get(2).getOrdinal());

        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.s");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "db2.B.c2", "temp0.s");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "db2.B.c2", "temp0.c2");
    }

    @Test
    public void consecutiveStarsUseCumulativeProjectionPositions() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, c2 INT);"
                        + "CREATE TABLE db2.B (c1 INT);"
                        + "CREATE TABLE T (x INT, y INT, z INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T(x,y,z) select A.*, B.* from A,B");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(0, instructions.get(0).getOrdinal());
        assertEquals(2, instructions.get(1).getOrdinal());

        List<Instruction> expanded = Instruction.toColumnInstructions(instructions, context);
        assertEquals(6, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "temp1.col1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING, "db1.A.c2", "temp1.col2");
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING, "db2.B.c1", "temp1.col3");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING, "temp1.col1", "T.x");
        assertInstruction(expanded.get(4), OperationType.COLUMN_MAPPING, "temp1.col2", "T.y");
        assertInstruction(expanded.get(5), OperationType.COLUMN_MAPPING, "temp1.col3", "T.z");
    }

    @Test
    public void starAndExplicitAliasUsePositionalIdentity() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db2.B (c1 INT);"
                        + "CREATE TABLE T (x INT, y INT);");

        LineageResult result = new DmlLineage(context)
                .parse("insert into T(x,y) select A.*, B.c1 as col1 from A,B");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertEquals(0, instructions.get(0).getOrdinal());
        assertEquals(1, instructions.get(1).getOrdinal());

        List<Instruction> expanded = Instruction.toColumnInstructions(instructions, context);
        assertEquals(4, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "temp1.col1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING, "db2.B.c1", "temp1.col2");
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING, "temp1.col1", "T.x");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING, "temp1.col2", "T.y");
    }

    @Test
    public void innerJoinUsingMergesBothSides() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (id INT, c1 INT);"
                        + "CREATE TABLE db2.B (id INT, c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select id from A join B using (id)");

        assertEquals(2, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.id", "temp0.id");
        assertInstruction(result.getInstructions().get(1),
                OperationType.COLUMN_MAPPING, "db2.B.id", "temp0.id");
    }

    @Test
    public void leftJoinUsingUsesTheLeftSide() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (id INT, c1 INT);"
                        + "CREATE TABLE db2.B (id INT, c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select id from A left join B using (id)");

        assertEquals(1, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.id", "temp0.id");
    }

    @Test
    public void rightJoinUsingUsesTheRightSide() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (id INT, c1 INT);"
                        + "CREATE TABLE db2.B (id INT, c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select id from A right join B using (id)");

        assertEquals(1, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db2.B.id", "temp0.id");
    }

    @Test
    public void punctuationInSourceAndTargetColumnsIsPreserved() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.D (`x,y` INT, `x-y.z` INT);");

        LineageResult insert = new DmlLineage(context)
                .parse("INSERT INTO D(`x,y`) SELECT c1 FROM A");
        List<Instruction> expanded = Instruction.toColumnInstructions(
                insert.getInstructions(), context);
        assertEquals(2, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING,
                "db1.A.c1", "temp1.c1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING,
                "temp1.c1", "db1.D.\"x,y\"");

        ScanResult scan = new LineageScanner(context).scan("SELECT `x-y.z` FROM D;");
        assertEquals(0, scan.getFailures().size());

        Set<Column> sources = scan.getGraph()
                .getSources(Column.temporary("stmt0.temp0.\"x-y.z\""));
        assertEquals(1, sources.size());
        Column source = sources.iterator().next();
        assertEquals("db1.D", source.getTableName());
        assertEquals("x-y.z", source.getColumnName());
    }

    @Test
    public void joinUsingDoesNotMergeQuotedDifferentCase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (\"C1\" INT, c1 INT);"
                + "CREATE TABLE b (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        ScanResult scan = new LineageScanner(context)
                .scan("SELECT \"C1\" FROM a RIGHT JOIN b USING (c1);");

        assertEquals(1, scan.getResults().size());
        assertEquals(0, scan.getFailures().size());
        Set<Column> sources = scan.getGraph()
                .getSources(Column.temporary("stmt0.temp0.C1"));
        assertEquals(columns("a.C1"), sources);
        assertFalse(sources.contains(new Column("b.C1")));
    }

    @Test
    public void quotedAndUnquotedColumnsStayDistinctInPostgres() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (\"C1\" INT, c1 INT);"
                + "CREATE TABLE b (\"C1\" INT, c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        ScanResult scan = new LineageScanner(context).scan(
                "SELECT c1 AS low, \"C1\" AS high FROM a;"
                        + "INSERT INTO b(\"C1\", c1) SELECT \"C1\", c1 FROM a;");

        assertEquals(2, scan.getResults().size());
        assertEquals(0, scan.getFailures().size());
        assertEquals(columns("a.c1"),
                scan.getGraph().getSources(Column.temporary("stmt0.temp0.low")));
        assertEquals(columns("a.C1"),
                scan.getGraph().getSources(Column.temporary("stmt0.temp0.high")));
        assertEquals(columns("a.C1"), scan.getGraph().getSources(new Column("b.C1")));
        assertEquals(columns("a.c1"), scan.getGraph().getSources(new Column("b.c1")));
    }

    @Test
    public void targetTableNameWithParenthesesIsPreserved() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE `D(x)` (`x,y` INT);");

        ScanResult scan = new LineageScanner(context)
                .scan("INSERT INTO `D(x)`(`x,y`) SELECT c1 FROM A;");

        assertEquals(1, scan.getResults().size());
        assertEquals(0, scan.getFailures().size());
        Set<Column> sources = scan.getGraph()
                .getSources(new Column("`D(x)`.\"x,y\""));
        assertEquals(1, sources.size());
        assertTrue(sources.contains(new Column("db1.A.c1")));
    }

    @Test
    public void derivedTableUsesBareTargetColumnNames() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select * from (select A.c1, B.c2 from A,B) as T");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "db1.A.c1", "T.c1");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "db2.B.c2", "T.c2");
        assertInstruction(instructions.get(2), OperationType.COLUMNS_INJECT, "T", "temp0");
    }

    @Test
    public void whereInSubqueryCollectsTablesWithoutOutputMapping() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, c2 INT, c3 INT, ci INT);"
                        + "CREATE TABLE db2.B (c1 INT);");

        DmlLineage lineage = new DmlLineage(context);
        LineageResult result = lineage
                .parse("select c1,c2,c3 from A where ci in (select c1 from B)");

        assertEquals(3, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
        assertInstruction(result.getInstructions().get(1),
                OperationType.COLUMN_MAPPING, "db1.A.c2", "temp0.c2");
        assertInstruction(result.getInstructions().get(2),
                OperationType.COLUMN_MAPPING, "db1.A.c3", "temp0.c3");

        Set<String> expectedTables = new HashSet<>(Arrays.asList("db1.A", "db2.B"));
        assertEquals(expectedTables, lineage.getTables());
    }

    @Test
    public void updateJoinProducesColumnLineage() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, id INT);"
                        + "CREATE TABLE db2.B (c2 INT, id INT);");

        DmlLineage lineage = new DmlLineage(context);
        LineageResult result = lineage
                .parse("update A join B on A.id = B.id set A.c1 = B.c2");

        assertEquals(1, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db2.B.c2", "db1.A.c1");
        assertEquals(new HashSet<>(Arrays.asList("db1.A", "db2.B")), lineage.getTables());
    }

    @Test
    public void updateJoinWithAliasesUsesFullyQualifiedNames() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT, id INT);"
                        + "CREATE TABLE db2.B (c2 INT, id INT);");

        LineageResult result = new DmlLineage(context)
                .parse("update A a join B b on a.id = b.id set a.c1 = b.c2");

        List<Instruction> instructions = result.getInstructions();
        assertEquals(3, instructions.size());
        assertInstruction(instructions.get(0), OperationType.TABLE_MAPPING, "db1.A", "a");
        assertInstruction(instructions.get(1), OperationType.TABLE_MAPPING, "db2.B", "b");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "db2.B.c2", "db1.A.c1");
    }

    @Test
    public void quotedIdentifiersUseNormalizedNames() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE `db1`.`A` (`c1` INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select `A`.`c1` from `A`");

        assertEquals(1, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
    }

    @Test
    public void crossDatabaseColumnsKeepTheirDatabase() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");

        LineageResult result = new DmlLineage(context)
                .parse("select db1.A.c1, db2.B.c2 from db1.A, db2.B");

        assertEquals(2, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
        assertInstruction(result.getInstructions().get(1),
                OperationType.COLUMN_MAPPING, "db2.B.c2", "temp0.c2");
    }

    @Test
    public void extractUnitIsNotAColumnAndItsSourceIsTraced() {
        MetadataContext context = mysqlContext("CREATE TABLE t (d DATE, x INT);");

        LineageResult result = new DmlLineage(context)
                .parse("SELECT extract(year FROM d) + x AS y FROM t");

        assertEquals(2, result.getInstructions().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("t.d"), new Column("t.x"))),
                new HashSet<>(Arrays.asList(
                        new Column(result.getInstructions().get(0).getFrom()),
                        new Column(result.getInstructions().get(1).getFrom()))));
        assertTrue(result.getInstructions().toString().contains("temp0.y"));
    }

    private static MetadataContext mysqlContext(String ddl) {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(ddl);
        return MetadataContext.of(catalog);
    }

    private static Set<Column> columns(String... names) {
        Set<Column> columns = new HashSet<>();
        for (String name : names) {
            columns.add(new Column(name));
        }
        return columns;
    }

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        assertEquals("operation", operation, instruction.getOperation());
        assertEquals("from", from, instruction.getFrom());
        assertEquals("to", to, instruction.getTo());
    }
}
