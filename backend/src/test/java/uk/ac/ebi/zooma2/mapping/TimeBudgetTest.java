package uk.ac.ebi.zooma2.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3PropertyMappingDto;
import uk.ac.ebi.zooma2.matcher.MatchContext;
import uk.ac.ebi.zooma2.matcher.OlsLexicalMatcher;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.Bioregistry;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.search.AnnotationEngine;
import uk.ac.ebi.zooma2.util.Diagnostics;

/** A per-property time budget bounds the chained stage timeouts, and running out is reported (issue #22, point 5). */
class TimeBudgetTest {

    @Test
    void budgetArithmetic() {
        MatchContext c = new MatchContext("q", null, null, "m");
        assertFalse(c.hasBudget());
        assertEquals(Long.MAX_VALUE, c.remainingMillis());
        assertEquals(30_000, c.timeoutWithin(30_000), "no budget: timeouts untouched");

        c.startBudget(10_000);
        assertTrue(c.hasBudget());
        assertTrue(c.remainingMillis() > 9_000 && c.remainingMillis() <= 10_000);
        assertTrue(c.timeoutWithin(60_000) <= 10_000, "a stage timeout is capped to what remains");
        assertEquals(1_000, c.timeoutWithin(1_000));

        c.startBudget(0);
        assertFalse(c.hasBudget());
    }

    @Test
    void anExpiredBudgetSkipsTheCallAndWarns() throws Exception {
        List<String> calls = new ArrayList<>();
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public List<OlsTerm> findByFuzzySearch(String q, int size, int timeoutMs, Collection<String> ids) { calls.add(q); return List.of(); }
        };
        MatchContext c = new MatchContext("q", null, null, "m");
        c.startBudget(1);
        Thread.sleep(5);
        assertTrue(c.isExpired());
        List<String> sink = Diagnostics.begin();
        try {
            assertTrue(new OlsLexicalMatcher(stub).findMatches(c).isEmpty());
        } finally {
            Diagnostics.end();
        }
        assertTrue(calls.isEmpty(), "no OLS call once the budget is gone");
        assertEquals(1, sink.size());
        assertTrue(sink.get(0).startsWith("Time budget exhausted"));
    }

    @Test
    void aPropertyThatRunsOutOfBudgetIsFlaggedTruncated() {
        AnnotationEngine slowEngine = new AnnotationEngine(new OlsClientRepo()) {
            @Override public List<Annotation> annotateShallow(MatchContext c) {
                try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ArrayList<>();
            }
        };
        StringMapper mapper = new StringMapper(slowEngine, new OlsClientRepo(), new PrefixMap(Bioregistry.fromSnapshot()), 20);
        StringToMap s = new StringToMap();
        s.textToMap = "x";

        List<MapResult> results = mapper.map(s, List.of(), Filter.fromLists(null, null, null, true), "m", false, null);
        V3PropertyMappingDto dto = V3PropertyMappingDto.fromResults(null, "x", results);

        assertEquals(Boolean.TRUE, dto.truncated);
        assertTrue(dto.warnings.get(0).startsWith("Time budget of 20 ms exhausted"), dto.warnings.get(0));

        StringMapper unbounded = new StringMapper(slowEngine, new OlsClientRepo(), new PrefixMap(Bioregistry.fromSnapshot()), 0);
        assertNull(V3PropertyMappingDto.fromResults(null, "x", unbounded.map(s, List.of(), Filter.fromLists(null, null, null, true), "m", false, null)).truncated);
    }
}
