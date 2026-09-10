package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/** With target ontologies, retrieval is scoped to them rather than filtered after a global top-k (issue #20). */
class ScopedRetrievalTest {

    /** Records every call; answers with one term per call, tagged by scope, plus a shared term with differing scores. */
    private static class RecordingRepo extends OlsClientRepo {
        final List<String> lexicalCalls = new ArrayList<>();
        final List<String> embeddingCalls = new ArrayList<>();

        private static OlsTerm term(String iri, String ontology, Double score) {
            OlsTerm t = new OlsTerm();
            t.iri = iri; t.label = iri; t.ontology_name = ontology; t.score = score;
            return t;
        }

        @Override
        public List<OlsTerm> findByFuzzySearch(String q, int size, int timeoutMs, Collection<String> ontologyIds) {
            String scope = ontologyIds == null ? "global" : String.join("+", ontologyIds);
            lexicalCalls.add(scope);
            return new ArrayList<>(List.of(term("http://x/shared", "chebi", null), term("http://x/lex-" + scope, scope, null)));
        }

        @Override
        public Collection<OlsTerm> findByEmbeddingSearch(String q, String model, String ontologyId, int size, int timeoutMs) {
            String scope = ontologyId == null ? "global" : ontologyId;
            embeddingCalls.add(q + "@" + scope);
            double sharedScore = ontologyId == null ? 0.80 : 0.95; // the scoped call sees the term closer
            return List.of(term("http://x/shared", "chebi", sharedScore), term("http://x/emb-" + scope, scope, 0.9));
        }
    }

    private static MatchContext context(String query, Boolean includeOthers, String... targets) {
        Filter f = targets.length == 0 ? Filter.fromLists(null, null, null, true) : Filter.fromLists(null, null, List.of(targets), includeOthers);
        return new MatchContext(query, null, f, "m");
    }

    private static List<String> iris(List<Annotation> annotations) {
        return annotations.stream().map(a -> a.semanticTags.get(0)).collect(Collectors.toList());
    }

    @Test
    void lexicalHardFilterQueriesOnlyTheTargetsInOneCall() {
        RecordingRepo repo = new RecordingRepo();
        var out = new OlsLexicalMatcher(repo).findMatches(context("vasopressin", false, "ecto", "chebi"));
        assertEquals(List.of("ecto+chebi"), repo.lexicalCalls);
        assertEquals(List.of("http://x/shared", "http://x/lex-ecto+chebi"), iris(out));
    }

    @Test
    void lexicalSoftPreferenceAddsTheScopedCallAndMergesByIri() {
        RecordingRepo repo = new RecordingRepo();
        var out = new OlsLexicalMatcher(repo).findMatches(context("vasopressin", true, "ecto"));
        assertEquals(List.of("global", "ecto"), repo.lexicalCalls);
        assertEquals(List.of("http://x/shared", "http://x/lex-global", "http://x/lex-ecto"), iris(out), "shared term once");
    }

    @Test
    void lexicalWithoutTargetsIsGlobalAsBefore() {
        RecordingRepo repo = new RecordingRepo();
        new OlsLexicalMatcher(repo).findMatches(context("vasopressin", true));
        assertEquals(List.of("global"), repo.lexicalCalls);
    }

    @Test
    void embeddingHardFilterQueriesEachTargetAndSkipsTheGlobalCall() {
        RecordingRepo repo = new RecordingRepo();
        var matcher = new OlsEmbeddingMatcher(repo, 0.7, 100, 10, 1000, 5);
        var out = matcher.findMatches(context("vasopressin", false, "ecto", "envo"));
        assertEquals(List.of("vasopressin@ecto", "vasopressin@envo"), repo.embeddingCalls);
        assertEquals(3, out.size());
    }

    @Test
    void embeddingSoftPreferenceMergesGlobalAndScopedKeepingTheHigherScore() {
        RecordingRepo repo = new RecordingRepo();
        var matcher = new OlsEmbeddingMatcher(repo, 0.7, 100, 10, 1000, 5);
        var out = matcher.findMatches(context("vasopressin", true, "ecto"));
        assertEquals(List.of("vasopressin@global", "vasopressin@ecto"), repo.embeddingCalls);
        Annotation shared = out.stream().filter(a -> a.semanticTags.get(0).equals("http://x/shared")).findFirst().orElseThrow();
        assertEquals(0.95 * 0.89, shared.confidence, 1e-9, "the scoped call's higher score wins");
        assertEquals(3, out.size());
    }

    @Test
    void embeddingFallsBackToGlobalAboveTheScopedCap() {
        RecordingRepo repo = new RecordingRepo();
        var matcher = new OlsEmbeddingMatcher(repo, 0.7, 100, 10, 1000, 2);
        matcher.findMatches(context("vasopressin", false, "ecto", "envo", "chebi"));
        assertEquals(List.of("vasopressin@global"), repo.embeddingCalls, "three targets, cap two: global only, as before");
    }

    @Test
    void dualCasingStillAppliesToEveryScope() {
        RecordingRepo repo = new RecordingRepo();
        var matcher = new OlsEmbeddingMatcher(repo, 0.7, 100, 10, 1000, 5);
        matcher.findMatches(context("Cisplatin", true, "ecto"));
        assertEquals(List.of("Cisplatin@global", "Cisplatin@ecto", "cisplatin@global", "cisplatin@ecto"), repo.embeddingCalls);
        assertTrue(RetrievalScope.perOntologyTargets(context("x", true, "A", "a"), 5).equals(List.of("a")), "targets are lower-cased and de-duplicated");
    }
}
