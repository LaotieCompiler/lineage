package com.laotie.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

public class AnalysisContextTest {

    @Test
    public void resetClearsTransientStateButKeepsSessionFlags() {
        AnalysisContext context = new AnalysisContext();
        context.reset();
        context.stackSources.push(new ArrayList<>());
        context.tempTables.add("t");
        context.currentOrdinal = 3;
        context.inTargetExpression = true;
        context.learningOnly = true;
        context.tempTableNum = 7;
        context.scalarSubqueryDepth = 2;
        context.learningBindings.put("a", "b");

        context.reset();

        assertTrue(context.stackSources.isEmpty());
        assertTrue(context.tempTables.isEmpty());
        assertTrue(context.targetOutputs.isEmpty());
        assertEquals(-1, context.currentOrdinal);
        assertFalse(context.inTargetExpression);
        assertTrue(context.learningOnly);
        assertEquals(7, context.tempTableNum);
        assertEquals(2, context.scalarSubqueryDepth);
        assertEquals("b", context.learningBindings.get("a"));
    }
}
