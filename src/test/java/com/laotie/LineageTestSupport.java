package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.HashSet;
import java.util.Set;

import com.alibaba.druid.DbType;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/** Shared fixtures for lineage tests. */
final class LineageTestSupport {

    private LineageTestSupport() {
    }

    static LineageOptions optional() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false);
    }

    static LineageOptions strict() {
        return LineageOptions.of(MetadataMode.STRICT, false);
    }

    static MetadataContext postgres(String ddl) {
        return catalog(DbType.postgresql, ddl);
    }

    static MetadataContext mysql(String ddl) {
        return catalog(DbType.mysql, ddl);
    }

    static MetadataContext catalog(DbType dialect, String ddl) {
        MetadataCatalog catalog = new MetadataCatalog(dialect);
        catalog.loadDdl(ddl);
        return MetadataContext.of(catalog);
    }

    static Set<Column> columns(String... names) {
        Set<Column> columns = new HashSet<>();
        for (String name : names) {
            columns.add(new Column(name));
        }
        return columns;
    }

    static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        assertNotNull("instruction should not be null", instruction);
        assertEquals("operation", operation, instruction.getOperation());
        assertEquals("from", from, instruction.getFrom());
        assertEquals("to", to, instruction.getTo());
    }
}
