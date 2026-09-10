package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.OlsTerm;

/**
 * resolveTerms must re-fetch cached terms that came from a search endpoint and
 * so lack the metadata a full OLS record carries (issue #13, point 3).
 */
class TermCacheEntryCompletenessTest {

    private static OlsTerm term(Boolean obsolete, String replacedBy, Map<String, List<String>> annotation) {
        OlsTerm t = new OlsTerm();
        t.iri = "http://purl.obolibrary.org/obo/GO_0000003";
        t.label = "x";
        t.is_obsolete = obsolete;
        t.term_replaced_by = replacedBy;
        t.annotation = annotation;
        return t;
    }

    @Test
    void unknownObsoleteFlagIsIncomplete() {
        assertFalse(OlsClientRepo.isCompleteCacheEntry(term(null, null, null)));
    }

    @Test
    void liveTermIsComplete() {
        assertTrue(OlsClientRepo.isCompleteCacheEntry(term(false, null, null)));
    }

    @Test
    void obsoleteTermWithoutAnyReplacementInfoIsIncomplete() {
        // The shape of an llm_search / tag_text entity: obsolete, nothing else
        assertFalse(OlsClientRepo.isCompleteCacheEntry(term(true, null, null)));
    }

    @Test
    void obsoleteTermWithPointerIsComplete() {
        assertTrue(OlsClientRepo.isCompleteCacheEntry(term(true, "GO_0022414", null)));
    }

    @Test
    void obsoleteTermWithConsiderAnnotationIsComplete() {
        assertTrue(OlsClientRepo.isCompleteCacheEntry(term(true, null, Map.of("consider", List.of("GO:0022414")))));
    }

    @Test
    void obsoleteTermFromFullRecordWithNoReplacementIsComplete() {
        // A full OLS record always carries the annotation block, even when empty
        assertTrue(OlsClientRepo.isCompleteCacheEntry(term(true, null, new HashMap<>())));
    }
}
