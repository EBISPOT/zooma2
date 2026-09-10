package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.search.EscalationPolicy;
import uk.ac.ebi.zooma2.util.StringSimilarity;

/**
 * A bare mention must reach the terms whose labels embed it (issue #26):
 * "cadmium" finds "exposure to cadmium" on query coverage, not on the
 * symmetric overlap that made recall depend on synonym length.
 */
class LexicalContainmentTest {

    private static final double TIGHTEST = EvidenceTier.PARTIAL.confidence(1.0); // 0.89

    private static OlsTerm term(String label, String iri, String... synonyms) {
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.label = label;
        t.short_form = iri.substring(iri.lastIndexOf('/') + 1);
        t.ontology_name = "ecto";
        t.synonyms = synonyms.length == 0 ? null : List.of(synonyms);
        return t;
    }

    private static Map<String, Annotation> match(String query, Filter filter, OlsTerm... hits) {
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public List<OlsTerm> findByFuzzySearch(String q, int size, int timeoutMs) {
                return new ArrayList<>(List.of(hits));
            }
            @Override
            public List<OlsTerm> findByFuzzySearch(String q, int size, int timeoutMs, Collection<String> ontologyIds) {
                return new ArrayList<>(List.of(hits));
            }
        };
        return new OlsLexicalMatcher(stub).findMatches(new MatchContext(query, null, filter, "m")).stream()
            .collect(Collectors.toMap(a -> a.semanticTags.get(0), Function.identity()));
    }

    private static Map<String, Annotation> match(String query, OlsTerm... hits) {
        return match(query, null, hits);
    }

    @Test
    void theTightestContainingLabelIsTheOntologysTermForTheQuery() {
        Annotation a = match("cadmium", term("exposure to cadmium", "http://x/ECTO_0001566")).get("http://x/ECTO_0001566");

        assertEquals("OLS_LEXICAL_CONTAINED", a.mappingProvenance.get(0).matchType);
        assertEquals(TIGHTEST, a.confidence, 1e-9);
        assertEquals("exposure to cadmium", a.mappingProvenance.get(0).matchedText);
        assertTrue(a.confidence >= EscalationPolicy.MIN_SATISFYING_CONFIDENCE,
            "a contained bare mention settles the shallow pass instead of falling to embedding guesses");
    }

    @Test
    void longerContainingLabelsAreItsSpecialisations() {
        var out = match("cadmium",
            term("exposure to cadmium selenide nanoparticle", "http://x/ECTO_9002274"),
            term("exposure to cadmium via ingestion", "http://x/ECTO_0900005"),
            term("exposure to cadmium", "http://x/ECTO_0001566"));

        double plain = out.get("http://x/ECTO_0001566").confidence;
        double ingestion = out.get("http://x/ECTO_0900005").confidence;
        double nanoparticle = out.get("http://x/ECTO_9002274").confidence;
        assertEquals(TIGHTEST, plain, 1e-9);
        // content tokens: {exposure, cadmium} = 2 is the tightest; ingestion has 3, nanoparticle 4
        assertEquals(EvidenceTier.PARTIAL.confidence(StringSimilarity.containmentScore(2, 3)), ingestion, 1e-9);
        assertEquals(EvidenceTier.PARTIAL.confidence(StringSimilarity.containmentScore(2, 4)), nanoparticle, 1e-9);
        assertTrue(plain > ingestion && ingestion > nanoparticle);
    }

    @Test
    void anExactMatchMakesEveryContainingLabelASpecialisation() {
        var out = match("cadmium",
            term("cadmium", "http://x/CHEBI_22977"),
            term("cadmium chloride", "http://x/CHEBI_35456"),
            term("exposure to cadmium", "http://x/ECTO_0001566"));

        assertEquals(1.0, out.get("http://x/CHEBI_22977").confidence, 1e-9);
        assertEquals("OLS_LEXICAL_FUZZY_LABEL", out.get("http://x/CHEBI_22977").mappingProvenance.get(0).matchType);
        assertEquals(EvidenceTier.PARTIAL.confidence(0.75), out.get("http://x/CHEBI_35456").confidence, 1e-9);
        assertEquals(EvidenceTier.PARTIAL.confidence(0.75), out.get("http://x/ECTO_0001566").confidence, 1e-9);

        // an exact synonym counts the same way
        var viaSynonym = match("cadmium",
            term("cadmium atom", "http://x/CHEBI_22977", "cadmium"),
            term("exposure to cadmium", "http://x/ECTO_0001566"));
        assertEquals("OLS_LEXICAL_FUZZY_SYNONYM", viaSynonym.get("http://x/CHEBI_22977").mappingProvenance.get(0).matchType);
        assertEquals(EvidenceTier.PARTIAL.confidence(0.75), viaSynonym.get("http://x/ECTO_0001566").confidence, 1e-9);
    }

    @Test
    void definingOnlyMeasuresTightnessAmongTheTargetsOwnTerms() {
        // ECTO's file imports CHEBI "cadmium atom" (synonym "cadmium"); with definingOnly it can
        // never be returned, so it must not demote ECTO's own "exposure to cadmium".
        OlsTerm imported = term("cadmium atom", "http://purl.obolibrary.org/obo/CHEBI_22977", "cadmium");
        OlsTerm own = term("exposure to cadmium", "http://purl.obolibrary.org/obo/ECTO_0001566");

        Filter definingOnly = Filter.fromLists(null, null, List.of("ecto"), false, true);
        var out = match("cadmium", definingOnly, imported, own);
        assertEquals(TIGHTEST, out.get(own.iri).confidence, 1e-9);
        assertEquals(0.9, out.get(imported.iri).confidence, 1e-9, "the imported synonym match itself is unchanged; the filter drops it later");

        Filter hardFilter = Filter.fromLists(null, null, List.of("ecto"), false);
        var out2 = match("cadmium", hardFilter, imported, own);
        assertEquals(EvidenceTier.PARTIAL.confidence(0.75), out2.get(own.iri).confidence, 1e-9,
            "without definingOnly the imported term is a legitimate answer and the ECTO term its specialisation");
    }

    @Test
    void containmentThroughASynonymReportsTheTightestString() {
        Annotation a = match("cadmium", term("cadmium molecular entity exposure", "http://x/1", "cadmium exposure")).get("http://x/1");
        assertEquals("cadmium exposure", a.mappingProvenance.get(0).matchedText);
        assertEquals(TIGHTEST, a.confidence, 1e-9);

        // on a tie the label is reported
        Annotation b = match("cisplatin", term("exposure to cisplatin", "http://x/2", "cisplatin exposure")).get("http://x/2");
        assertEquals("exposure to cisplatin", b.mappingProvenance.get(0).matchedText);
        assertEquals(TIGHTEST, b.confidence, 1e-9);
    }

    @Test
    void exactMatchesAndLongQueriesAreUnchanged() {
        Annotation exact = match("cadmium", term("cadmium", "http://x/CHEBI_22977")).get("http://x/CHEBI_22977");
        assertEquals("OLS_LEXICAL_FUZZY_LABEL", exact.mappingProvenance.get(0).matchType);
        assertEquals(1.0, exact.confidence, 1e-9);

        // the other direction is still the discounted fuzzy blend: a long query does not "contain" a short label
        Annotation fuzzy = match("exposure to cadmium", term("cadmium", "http://x/CHEBI_22977")).get("http://x/CHEBI_22977");
        assertEquals("OLS_LEXICAL_FUZZY", fuzzy.mappingProvenance.get(0).matchType);
        assertEquals(0.70 * StringSimilarity.blended("exposure to cadmium", "cadmium"), fuzzy.confidence, 1e-9);
        assertTrue(fuzzy.confidence < EscalationPolicy.MIN_SATISFYING_CONFIDENCE);
    }
}
