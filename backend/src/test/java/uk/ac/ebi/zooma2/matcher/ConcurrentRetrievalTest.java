package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Independent OLS calls run concurrently (issue #22, points 1 and 2). Proven with a
 * latch every call must reach before any can return: a sequential run would wait
 * on the first call forever.
 */
class ConcurrentRetrievalTest {

    private static OlsTerm term(String iri, String ontology, double score) {
        OlsTerm t = new OlsTerm();
        t.iri = iri; t.label = iri; t.ontology_name = ontology; t.score = score;
        return t;
    }

    @Test
    void dualCasingAndScopedEmbeddingCallsRunConcurrentlyAndMergeInOrder() {
        int expectedCalls = 2 * 3; // two casings x (global + two scoped ontologies)
        CountDownLatch inFlight = new CountDownLatch(expectedCalls);
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public Collection<OlsTerm> findByEmbeddingSearch(String q, String model, String ontologyId, int size, int timeoutMs) {
                inFlight.countDown();
                try {
                    if (!inFlight.await(3, TimeUnit.SECONDS)) throw new AssertionError("calls did not overlap: " + q + "@" + ontologyId);
                } catch (InterruptedException e) { throw new AssertionError(e); }
                String scope = ontologyId == null ? "global" : ontologyId;
                return List.of(term("http://x/" + q + "@" + scope, scope, 0.9), term("http://x/shared", "chebi", ontologyId == null ? 0.8 : 0.85));
            }
        };
        var matcher = new OlsEmbeddingMatcher(stub, 0.7, 100, 10, 1000, 5);
        MatchContext context = new MatchContext("Cisplatin", null, Filter.fromLists(null, null, List.of("ecto", "envo"), true), "m");

        List<Annotation> out = matcher.findMatches(context);

        List<String> iris = out.stream().map(a -> a.semanticTags.get(0)).collect(Collectors.toList());
        assertEquals(List.of("http://x/Cisplatin@global", "http://x/shared", "http://x/Cisplatin@ecto", "http://x/Cisplatin@envo",
            "http://x/cisplatin@global", "http://x/cisplatin@ecto", "http://x/cisplatin@envo"), iris, "merged in the sequential order");
        assertEquals(0.85 * 0.89, out.get(1).confidence, 1e-9, "higher score kept for the shared term");
    }

    @Test
    void similarExpansionQueriesEverySeedConcurrently() {
        CountDownLatch inFlight = new CountDownLatch(3);
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public List<OlsTerm> findSimilarTerms(String termIri, String model, int size, int timeoutMs) {
                inFlight.countDown();
                try {
                    if (!inFlight.await(3, TimeUnit.SECONDS)) throw new AssertionError("seed calls did not overlap");
                } catch (InterruptedException e) { throw new AssertionError(e); }
                return List.of(term("http://purl.obolibrary.org/obo/ECTO_" + termIri.substring(termIri.length() - 1), "ecto", 0.9));
            }
        };
        List<Annotation> seeds = List.of(seed("http://x/1"), seed("http://x/2"), seed("http://x/3"));
        MatchContext context = new MatchContext("q", null, Filter.fromLists(null, null, List.of("ecto"), false), "m").withPreviousResults(seeds);

        List<Annotation> out = new OlsEmbeddingSimilarMatcher(stub, null, 0.7).findMatches(context);

        assertEquals(List.of("http://purl.obolibrary.org/obo/ECTO_1", "http://purl.obolibrary.org/obo/ECTO_2", "http://purl.obolibrary.org/obo/ECTO_3"),
            out.stream().map(a -> a.semanticTags.get(0)).collect(Collectors.toList()), "seed order kept");
        assertTrue(out.stream().allMatch(a -> a.confidence > 0.6));
    }

    private static Annotation seed(String iri) {
        Annotation a = new Annotation();
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyValue = "q";
        a.semanticTags = List.of(iri);
        a.confidence = 1.0;
        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.name = "chebi";
        a.provenance.evidence = "OLS_TEXT_TAGGER";
        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical("ols:chebi", "OLS_TEXT_TAGGER", "q", "q", iri, 1.0));
        return a;
    }
}
