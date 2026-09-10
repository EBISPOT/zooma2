package uk.ac.ebi.zooma2.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.matcher.MatchContext;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/** The deep pass must not re-fetch what the shallow pass already retrieved (issue #22, point 3). */
class DeepRefetchTest {

    /** Embedding search stub: a fixed index of {@code indexSize} terms with descending scores, paged by size. */
    private static class IndexRepo extends OlsClientRepo {
        final int indexSize;
        final List<Integer> sizesRequested = new ArrayList<>();

        IndexRepo(int indexSize) { this.indexSize = indexSize; }

        @Override
        public Collection<OlsTerm> findByEmbeddingSearch(String q, String model, String ontologyId, int size, int timeoutMs) {
            sizesRequested.add(size);
            return IntStream.range(0, Math.min(size, indexSize)).mapToObj(i -> {
                OlsTerm t = new OlsTerm();
                t.iri = "http://x/" + i; t.label = "t" + i; t.ontology_name = "efo"; t.score = 0.99 - i * 0.002;
                return t;
            }).collect(Collectors.toList());
        }

        @Override
        public List<OlsTerm> findByFuzzySearch(String q, int size, int timeoutMs, Collection<String> ontologyIds) {
            // The lexical matcher also finds term 12; the deep embedding hit for it must still be reported
            OlsTerm t = new OlsTerm();
            t.iri = "http://x/12"; t.label = "t12"; t.ontology_name = "efo";
            return new ArrayList<>(List.of(t));
        }
    }

    @Test
    void aShortShallowPageDoesNotSkipTheDeepSearch() {
        // OLS's approximate nearest-neighbour search is not monotonic across page
        // sizes, so a short or low-scoring shallow page is no proof the index is
        // exhausted; the deep page is always fetched and only its repeats are dropped.
        IndexRepo repo = new IndexRepo(3);
        AnnotationEngine engine = new AnnotationEngine(repo);
        MatchContext context = new MatchContext("q", null, Filter.fromLists(null, null, List.of("efo"), false), "m");

        List<Annotation> shallow = engine.annotateShallow(context);
        assertEquals(4, shallow.size(), "three embedding hits plus the lexical one");

        List<Annotation> deep = engine.annotateDeep(context, shallow);
        assertTrue(deep.isEmpty(), "the same three terms came back and were filtered");
        assertEquals(List.of(10, 100), repo.sizesRequested);
    }

    @Test
    void deepSearchReturnsOnlyWhatTheShallowSearchDidNotHave() {
        IndexRepo repo = new IndexRepo(25);
        AnnotationEngine engine = new AnnotationEngine(repo);
        MatchContext context = new MatchContext("q", null, Filter.fromLists(null, null, List.of("efo"), false), "m");

        List<Annotation> shallow = engine.annotateShallow(context);
        assertEquals(11, shallow.size(), "ten embedding hits plus the lexical one");

        List<Annotation> deep = engine.annotateDeep(context, shallow);
        assertEquals(15, deep.size(), "the top ten embedding hits are not reported a second time");
        assertTrue(deep.stream().anyMatch(a -> a.semanticTags.get(0).equals("http://x/12")), "a lexical hit does not hide the deep embedding evidence for the same term");
        assertTrue(deep.stream().noneMatch(a -> a.semanticTags.get(0).matches("http://x/[0-9]$")), "ranks 0-9 filtered");
        assertEquals(List.of(10, 100), repo.sizesRequested);
    }
}
