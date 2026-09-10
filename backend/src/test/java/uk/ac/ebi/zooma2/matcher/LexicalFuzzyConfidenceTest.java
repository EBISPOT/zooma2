package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.util.StringSimilarity;

/** Non-exact fuzzy hits must keep the retrieval signal instead of scoring zero on a single-token variant (issue #19, point 1). */
class LexicalFuzzyConfidenceTest {

    private static OlsTerm term(String label, String iri) {
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.label = label;
        t.short_form = label;
        t.ontology_name = "efo";
        return t;
    }

    private static List<Annotation> match(String query, OlsTerm... hits) {
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public List<OlsTerm> findByFuzzySearch(String q, int size, int timeoutMs) {
                return new ArrayList<>(List.of(hits));
            }
        };
        return new OlsLexicalMatcher(stub).findMatches(new MatchContext(query, null, null, "m"));
    }

    @Test
    void singleTokenTypoNoLongerScoresZero() {
        Annotation a = match("melanomma", term("melanoma", "http://x/1")).get(0);
        assertEquals(0.70 * StringSimilarity.blended("melanomma", "melanoma"), a.confidence, 1e-9);
        assertEquals(0.70 * (1 - 1.0 / 9), a.confidence, 1e-9);
        assertEquals("OLS_LEXICAL_FUZZY", a.mappingProvenance.get(0).matchType);
    }

    @Test
    void subPhraseAndVariantExamples() {
        // "cooked broccoli" / "broccoli": Jaccard 0.5, character similarity 1 - 7/15 = 0.53; the blend takes the larger
        assertEquals(0.70 * (1 - 7.0 / 15), match("cooked broccoli", term("broccoli", "http://x/2")).get(0).confidence, 1e-9);
        assertEquals(0.70 * StringSimilarity.blended("left tibia", "tibia"), match("left tibia", term("tibia", "http://x/3")).get(0).confidence, 1e-9);
        double longQt = match("Long QT syndrome 3/6", term("long QT syndrome 3", "http://x/4")).get(0).confidence;
        assertEquals(0.70 * StringSimilarity.blended("Long QT syndrome 3/6", "long QT syndrome 3"), longQt, 1e-9);
    }

    @Test
    void exactLabelStillGetsTheFullMatchTier() {
        Annotation a = match("Melanoma", term("melanoma", "http://x/1")).get(0);
        assertEquals(1.0, a.confidence, 1e-9);
        assertEquals("OLS_LEXICAL_FUZZY_LABEL", a.mappingProvenance.get(0).matchType);
    }

    @Test
    void entitiesWithoutAnIriAreSkippedInsteadOfFailingTheProperty() {
        List<Annotation> out = match("melanoma", term("melanoma", null), term("melanoma", "http://x/1"));
        assertEquals(1, out.size());
        assertEquals("http://x/1", out.get(0).semanticTags.get(0));
    }
}
