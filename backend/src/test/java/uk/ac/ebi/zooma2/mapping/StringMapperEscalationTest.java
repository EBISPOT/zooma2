package uk.ac.ebi.zooma2.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.matcher.MatchContext;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.search.AnnotationEngine;

/**
 * The escalation decision is made once, over engine + tagger results, and the
 * deep pass completes a shallow run without repeating Phase 1 (issue #14).
 */
class StringMapperEscalationTest {

    private static PrefixMap prefixMap;

    @BeforeAll
    static void init() {
        prefixMap = new PrefixMap(); // Bioregistry fetched once
    }

    /** Engine stub: canned phases, call counting, no OLS. */
    private static class StubEngine extends AnnotationEngine {
        final List<Annotation> shallow;
        final List<Annotation> deep;
        int shallowCalls = 0;
        int deepCalls = 0;
        List<Annotation> seedsSeen;

        StubEngine(List<Annotation> shallow, List<Annotation> deep) {
            super(new OlsClientRepo());
            this.shallow = shallow;
            this.deep = deep;
        }

        @Override
        public List<Annotation> annotateShallow(MatchContext context) {
            shallowCalls++;
            return new ArrayList<>(shallow);
        }

        @Override
        public List<Annotation> annotateDeep(MatchContext context, List<Annotation> shallowResults) {
            deepCalls++;
            seedsSeen = shallowResults;
            return new ArrayList<>(deep);
        }
    }

    private static Annotation annotation(String shortForm, String ontology, double confidence, String matchType) {
        Annotation a = new Annotation();
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyValue = "vasopressin";
        String iri = "http://purl.obolibrary.org/obo/" + shortForm;
        a.semanticTags = List.of(iri);
        a.confidence = confidence;
        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.name = ontology;
        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical("ols:" + ontology, matchType, "vasopressin", "x", iri, confidence));
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.short_form = shortForm;
        t.label = shortForm;
        t.ontology_name = ontology;
        t.is_obsolete = false;
        a.resolvedTerm = t;
        return a;
    }

    private static final Filter ECTO = Filter.fromLists(null, null, List.of("ecto"), false);
    private static final StringToMap PROP = property();

    private static StringToMap property() {
        StringToMap s = new StringToMap();
        s.textToMap = "vasopressin";
        return s;
    }

    private static List<String> ids(List<MapResult> results) {
        return results.stream().map(r -> r.ontologyTermID).collect(Collectors.toList());
    }

    @Test
    void deepRunsWhenExplicitlyRequestedEvenIfPhaseOneIsEmpty() {
        StubEngine engine = new StubEngine(List.of(), List.of(annotation("ECTO_1", "ecto", 0.8, "OLS_EMBEDDING")));
        StringMapper mapper = new StringMapper(engine, new OlsClientRepo(), prefixMap);

        List<MapResult> results = mapper.map(PROP, List.of(), ECTO, "m", true, null);

        assertEquals(1, engine.deepCalls);
        assertEquals(List.of("ECTO_1"), ids(results));
    }

    @Test
    void deepNeverRunsWhenExplicitlyDisabled() {
        StubEngine engine = new StubEngine(List.of(annotation("CHEBI_1", "chebi", 1.0, "OLS_TEXT_TAGGER")), List.of());
        StringMapper mapper = new StringMapper(engine, new OlsClientRepo(), prefixMap);

        mapper.map(PROP, List.of(), ECTO, "m", false, null);

        assertEquals(0, engine.deepCalls);
    }

    @Test
    void autoEscalationConsidersTaggerResultsToo() {
        // The engine found nothing from ECTO, but the tagger did: no escalation
        StubEngine engine = new StubEngine(List.of(annotation("CHEBI_1", "chebi", 1.0, "OLS_TEXT_TAGGER")), List.of());
        StringMapper mapper = new StringMapper(engine, new OlsClientRepo(), prefixMap);
        Annotation taggerEcto = annotation("ECTO_2", "ecto", 0.9, "OLS_TEXT_TAGGER_SYNONYM");

        List<MapResult> results = mapper.map(PROP, List.of(taggerEcto), ECTO, "m", null, null);

        assertEquals(0, engine.deepCalls);
        assertEquals(List.of("CHEBI_1", "ECTO_2"), ids(results), "engine results first, tagger results after");
    }

    @Test
    void autoEscalatesWhenOnlyTheExcludedTermSettlesTheSearch() {
        StubEngine engine = new StubEngine(List.of(annotation("ECTO_1", "ecto", 1.0, "OLS_TEXT_TAGGER")),
                                           List.of(annotation("ECTO_9", "ecto", 0.75, "OLS_EMBEDDING")));
        StringMapper mapper = new StringMapper(engine, new OlsClientRepo(), prefixMap);

        assertEquals(0, mapDeepCalls(engine, mapper, null));
        assertEquals(1, mapDeepCalls(engine, mapper, List.of("ECTO_1")));
    }

    private static int mapDeepCalls(StubEngine engine, StringMapper mapper, List<String> excluded) {
        int before = engine.deepCalls;
        mapper.map(PROP, List.of(), ECTO, "m", null, excluded);
        return engine.deepCalls - before;
    }

    @Test
    void twoPassFlowCompletesTheShallowRunWithoutRepeatingPhaseOne() {
        Annotation shallowHit = annotation("CHEBI_1", "chebi", 1.0, "OLS_TEXT_TAGGER");
        StubEngine engine = new StubEngine(List.of(shallowHit), List.of(annotation("ECTO_3", "ecto", 0.8, "OLS_EMBEDDING")));
        StringMapper mapper = new StringMapper(engine, new OlsClientRepo(), prefixMap);
        Annotation tagger = annotation("MONDO_1", "mondo", 0.9, "OLS_TEXT_TAGGER_SYNONYM");

        StringMapper.MappingRun run = mapper.mapShallow(PROP, List.of(tagger), ECTO, "m", null, null);
        assertTrue(run.needsDeep);
        assertEquals(List.of("CHEBI_1", "MONDO_1"), ids(run.results));
        assertEquals(1, engine.shallowCalls);
        assertEquals(0, engine.deepCalls);

        List<MapResult> complete = mapper.mapDeep(run);
        assertEquals(1, engine.shallowCalls, "Phase 1 is not repeated");
        assertEquals(1, engine.deepCalls);
        assertEquals(1, engine.seedsSeen.size());
        assertSame(shallowHit, engine.seedsSeen.get(0), "the deep pass is seeded with the Phase-1 annotations");
        assertEquals(List.of("CHEBI_1", "ECTO_3", "MONDO_1"), ids(complete), "engine Phase 1, Phase 2, then tagger");
        assertFalse(mapper.mapShallow(PROP, List.of(tagger, annotation("ECTO_4", "ecto", 0.9, "OLS_TEXT_TAGGER")), ECTO, "m", null, null).needsDeep);
    }
}
