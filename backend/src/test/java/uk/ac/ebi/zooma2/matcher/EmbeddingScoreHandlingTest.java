package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/** A scoreless hit cannot be shown to clear min_similarity, and a null-IRI hit cannot be a mapping (issue #19, points 3 and 4). */
class EmbeddingScoreHandlingTest {

    private static OlsTerm hit(String iri, Double score) {
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.label = "x";
        t.ontology_name = "efo";
        t.score = score;
        return t;
    }

    @Test
    void onlyScoredHitsAboveTheThresholdWithAnIriBecomeAnnotations() {
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public Collection<OlsTerm> findByEmbeddingSearch(String q, String model, String ontologyId, int size, int timeoutMs) {
                return List.of(hit("http://x/none", null), hit("http://x/low", 0.5), hit(null, 0.99), hit("http://x/good", 0.9));
            }
        };
        List<Annotation> out = new OlsEmbeddingMatcher(stub, 0.7, 100, 10, 1000).findMatches(new MatchContext("query", null, null, "m"));

        assertEquals(1, out.size());
        assertEquals("http://x/good", out.get(0).semanticTags.get(0));
        assertEquals(0.9 * 0.89, out.get(0).confidence, 1e-9, "no invented default score");
    }
}
