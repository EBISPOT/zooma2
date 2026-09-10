package uk.ac.ebi.zooma2.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * The tagger annotations for a text are fetched once per batch and shared by every
 * property with that text, across threads and across the shallow/deep passes.
 * Converting them to {@link MapResult}s must therefore never modify them, and must
 * give the same answer no matter how many times (or from how many threads) it runs.
 */
class TaggerAnnotationConversionIsSideEffectFreeTest {

    private static final String LIVE_IRI = "http://www.ebi.ac.uk/efo/EFO_0000001";
    private static final String OBSOLETE_IRI = "http://www.ebi.ac.uk/efo/EFO_0000002";
    private static final String REPLACEMENT_IRI = "http://www.ebi.ac.uk/efo/EFO_0000003";
    private static final String DEAD_IRI = "http://www.ebi.ac.uk/efo/EFO_0000004";

    /** Shared: the constructor fetches the Bioregistry over the network. */
    private static PrefixMap prefixMap;

    @BeforeAll
    static void loadPrefixMap() {
        prefixMap = new PrefixMap();
    }

    /** Stubs OLS term resolution with a fixed set of terms; no network. */
    private static class StubOlsRepo extends OlsClientRepo {
        final Map<String, OlsTerm> terms = new HashMap<>();
        int resolveCalls = 0;

        StubOlsRepo() {
            terms.put(LIVE_IRI, term(LIVE_IRI, "EFO_0000001", "live term", false, null));
            // Obsolete, and OLS knows its replacement
            terms.put(OBSOLETE_IRI, term(OBSOLETE_IRI, "EFO_0000002", "obsolete term", true, REPLACEMENT_IRI));
            terms.put(REPLACEMENT_IRI, term(REPLACEMENT_IRI, "EFO_0000003", "replacement term", false, null));
            // Obsolete with no replacement: must be dropped
            terms.put(DEAD_IRI, term(DEAD_IRI, "EFO_0000004", "dead term", true, null));
        }

        @Override
        public synchronized Map<String, OlsTerm> resolveTerms(Collection<String> termIris) {
            resolveCalls++;
            Map<String, OlsTerm> out = new HashMap<>();
            for (String iri : termIris) {
                if (terms.containsKey(iri)) out.put(iri, terms.get(iri));
            }
            return out;
        }
    }

    private static OlsTerm term(String iri, String shortForm, String label, boolean obsolete, String replacedBy) {
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.short_form = shortForm;
        t.label = label;
        t.ontology_name = "efo";
        t.is_obsolete = obsolete;
        t.term_replaced_by = replacedBy;
        return t;
    }

    private static Annotation annotation(String tag, OlsTerm resolvedTerm, double confidence) {
        Annotation a = new Annotation();
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyType = "unspecified"; // what OlsTextTaggerMatcher sets
        a.annotatedProperty.propertyValue = "some text";
        a.semanticTags = List.of(tag);
        a.resolvedTerm = resolvedTerm;
        a.confidence = confidence;
        a.mappingProvenance = List.of(
            V3MappingProvenanceStepDto.lexical("ols:efo", "LABEL", "some text", "some text", tag, 1.0));
        return a;
    }

    /**
     * Mirrors what the tagger produces: one annotation resolved through OLS, one
     * carrying a pre-resolved obsolete term whose replacement metadata is missing
     * (so the mapper has to re-resolve it), and one obsolete term with no replacement.
     */
    private static List<Annotation> taggerAnnotations() {
        // Pre-resolved copy from a search hit: obsolete but without term_replaced_by
        OlsTerm partialObsolete = term(OBSOLETE_IRI, "EFO_0000002", "obsolete term", true, null);
        return List.of(
            annotation(LIVE_IRI, null, 1.0),
            annotation(OBSOLETE_IRI, partialObsolete, 0.9),
            annotation(DEAD_IRI, null, 0.8)
        );
    }

    private static StringToMap property(String propertyType) {
        StringToMap s = new StringToMap();
        s.textToMap = "some text";
        s.propertyType = propertyType;
        return s;
    }

    private static String describe(MapResult r) {
        String prov = r.mappingProvenance.stream()
            .map(p -> p.matchType + "->" + p.target)
            .collect(Collectors.joining(","));
        return r.textToMap + "|" + r.ontologyTermID + "|" + r.ontologyTermLabel + "|" + r.ontologyURI
            + "|" + r.mappingConfidence + "|" + prov;
    }

    @Test
    void convertingTwiceLeavesAnnotationsUntouchedAndGivesIdenticalResults() {
        StubOlsRepo repo = new StubOlsRepo();
        StringMapper mapper = new StringMapper(null, repo, prefixMap);
        List<Annotation> annotations = taggerAnnotations();

        // Snapshot the identity of every field the old code used to overwrite
        List<OlsTerm> resolvedBefore = annotations.stream().map(a -> a.resolvedTerm).collect(Collectors.toList());
        List<List<V3MappingProvenanceStepDto>> provenanceBefore =
            annotations.stream().map(a -> a.mappingProvenance).collect(Collectors.toList());

        List<MapResult> first = mapper.annotationsToMapResults(annotations, property("organism"), false);
        List<MapResult> second = mapper.annotationsToMapResults(annotations, property(null), true);

        for (int i = 0; i < annotations.size(); i++) {
            Annotation a = annotations.get(i);
            assertEquals("unspecified", a.annotatedProperty.propertyType, "propertyType written back into shared annotation");
            assertSame(resolvedBefore.get(i), a.resolvedTerm, "resolvedTerm overwritten on shared annotation");
            assertSame(provenanceBefore.get(i), a.mappingProvenance, "mappingProvenance reassigned on shared annotation");
            assertEquals(1, a.mappingProvenance.size(), "provenance step appended to shared annotation");
        }

        // The requested property type goes on the result, not the annotation
        assertTrue(first.stream().allMatch(r -> "organism".equals(r.propertyType)));
        assertTrue(second.stream().allMatch(r -> "unspecified".equals(r.propertyType)));

        // Everything else is identical between the two conversions
        assertEquals(
            first.stream().map(TaggerAnnotationConversionIsSideEffectFreeTest::describe).collect(Collectors.toList()),
            second.stream().map(TaggerAnnotationConversionIsSideEffectFreeTest::describe).collect(Collectors.toList()));

        // Live term kept, obsolete swapped for its replacement with ONE extra provenance
        // step, obsolete-without-replacement dropped
        assertEquals(2, first.size());
        assertEquals("EFO_0000001", first.get(0).ontologyTermID);
        assertEquals(1, first.get(0).mappingProvenance.size());
        assertEquals("EFO_0000003", first.get(1).ontologyTermID);
        assertEquals("replacement term", first.get(1).ontologyTermLabel);
        assertEquals(2, first.get(1).mappingProvenance.size());
        assertEquals("OBSOLETE_REPLACEMENT", first.get(1).mappingProvenance.get(1).matchType);
        assertEquals(2, second.get(1).mappingProvenance.size(), "second conversion must not append a duplicate replacement step");
    }

    @Test
    void concurrentConversionsWithDifferentPropertyTypesNeverCrossContaminate() throws Exception {
        StubOlsRepo repo = new StubOlsRepo();
        StringMapper mapper = new StringMapper(null, repo, prefixMap);
        List<Annotation> shared = taggerAnnotations();
        String[] types = {"organism", null, "cell type", "unspecified"};
        var problems = new ConcurrentLinkedQueue<String>();

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final String type = types[t % types.length];
                final String expected = type != null ? type : "unspecified";
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        List<MapResult> results = mapper.annotationsToMapResults(shared, property(type), i % 2 == 0);
                        if (results.size() != 2) problems.add("size " + results.size() + " for " + type);
                        for (MapResult r : results) {
                            if (!expected.equals(r.propertyType)) problems.add("got " + r.propertyType + " for " + type);
                        }
                        if (results.get(1).mappingProvenance.size() != 2) problems.add("provenance grew for " + type);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        }

        assertTrue(problems.isEmpty(), () -> problems.stream().limit(5).collect(Collectors.joining("; ")));
        for (Annotation a : shared) {
            assertEquals("unspecified", a.annotatedProperty.propertyType);
            assertEquals(1, a.mappingProvenance.size());
        }
    }
}
