package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * The bulk tagger sends each term with its spelling variants and credits a
 * variant's hits to the term at a discount (issue #21).
 */
class TaggerVariantAttributionTest {

    private static final String EFO_NEOPLASM = "http://www.ebi.ac.uk/efo/EFO_0000616";
    private static final String MONDO_NEOPLASM = "http://purl.obolibrary.org/obo/MONDO_0005070";
    private static final String RAT = "http://purl.obolibrary.org/obo/NCBITaxon_10116";
    private static final String SUBSTRING_HIT = "http://purl.obolibrary.org/obo/NCIT_C3262";

    private static PrefixMap prefixMap;

    @BeforeAll
    static void load() {
        prefixMap = new PrefixMap();
    }

    /** tag_text knows "tumour" (a label), "tumors" (a synonym and a label) and "rat"; nothing else. */
    private static class StubOlsRepo extends OlsClientRepo {
        List<String> requested;
        Set<String> failing = Set.of();

        @Override
        public TagTextResponse tagText(List<String> terms, List<String> ontologyIds) {
            requested = terms;
            Map<String, List<TagTextMatch>> matches = new HashMap<>();
            for (String term : terms) {
                if (failing.contains(term)) continue;
                switch (term) {
                    case "tumour" -> matches.put(term, List.of(
                        new TagTextMatch("tumour", EFO_NEOPLASM, "efo", 1.0, "LABEL", null, null, null, false)));
                    case "tumors" -> matches.put(term, List.of(
                        new TagTextMatch("tumour", EFO_NEOPLASM, "efo", 1.0, "synonym", null, null, null, false),
                        new TagTextMatch("tumors", MONDO_NEOPLASM, "mondo", 1.0, "LABEL", null, null, null, false),
                        new TagTextMatch("tumor", SUBSTRING_HIT, "ncit", 0.8, "LABEL", null, null, null, false)));
                    case "tumor" -> matches.put(term, List.of(
                        new TagTextMatch("tumour", EFO_NEOPLASM, "efo", 1.0, "synonym", null, null, null, false)));
                    case "rat" -> matches.put(term, List.of(
                        new TagTextMatch("rat", RAT, "ncbitaxon", 1.0, "LABEL", null, null, null, false)));
                    default -> matches.put(term, List.of());
                }
            }
            Set<String> failed = new java.util.HashSet<>(terms);
            failed.retainAll(failing);
            return new TagTextResponse(matches, failed, failed.isEmpty() ? null : "stub failure");
        }
    }

    @Test
    void variantHitsAreCreditedToTheTermAtADiscount() {
        var repo = new StubOlsRepo();
        var matcher = new OlsTextTaggerMatcher(repo, prefixMap);

        var byTerm = matcher.bulkTag(List.of("tumours")).byTerm;

        assertTrue(repo.requested.contains("tumours"), repo.requested.toString());
        assertTrue(repo.requested.contains("tumour"), repo.requested.toString());
        assertTrue(repo.requested.contains("tumors"), repo.requested.toString());

        var annotations = byTerm.get("tumours");
        assertEquals(2, annotations.size(), "the EFO term reached twice through variants is reported once, and a variant's substring hit not at all");
        assertFalse(annotations.stream().anyMatch(a -> a.semanticTags.contains(SUBSTRING_HIT)));
        var efo = annotations.get(0);
        assertEquals(EFO_NEOPLASM, efo.semanticTags.get(0));
        assertEquals(OlsTextTaggerMatcher.VARIANT_DISCOUNT, efo.confidence, 1e-9);
        assertEquals("OLS_TEXT_TAGGER", efo.mappingProvenance.get(0).matchType, "a label match stays a label match");
        assertEquals("tumour", efo.mappingProvenance.get(0).input, "the provenance names the spelling that matched");
        assertEquals("tumours", efo.annotatedProperty.propertyValue, "the annotation belongs to the term asked about");
        var mondo = annotations.get(1);
        assertEquals(MONDO_NEOPLASM, mondo.semanticTags.get(0));
        assertEquals("tumors", mondo.mappingProvenance.get(0).input);
    }

    @Test
    void directHitsKeepFullConfidenceAndWinOverTheirVariantTwins() {
        var repo = new StubOlsRepo();
        var matcher = new OlsTextTaggerMatcher(repo, prefixMap);

        var tumour = matcher.bulkTag(List.of("tumour")).byTerm.get("tumour");
        assertEquals(1, tumour.size(), "the synonym hit via 'tumor' duplicates the direct label hit");
        assertEquals(1.0, tumour.get(0).confidence, 1e-9);
        assertEquals("tumour", tumour.get(0).mappingProvenance.get(0).input);

        var rat = matcher.bulkTag(List.of("rat")).byTerm.get("rat");
        assertEquals(List.of("rat"), repo.requested, "a short word has no variants");
        assertEquals(1.0, rat.get(0).confidence, 1e-9);
    }

    @Test
    void onlyTheTermsThemselvesCountAsFailed() {
        var repo = new StubOlsRepo();
        var matcher = new OlsTextTaggerMatcher(repo, prefixMap);

        repo.failing = Set.of("tumour"); // a variant's request failed, the term's own succeeded
        var results = matcher.bulkTag(List.of("tumours"));
        assertEquals(Set.of(), results.failedTerms);
        assertTrue(results.byTerm.containsKey("tumours"));
        // the EFO term is still reached, through the surviving "tumors" variant's synonym hit
        assertEquals(List.of(EFO_NEOPLASM, MONDO_NEOPLASM), results.byTerm.get("tumours").stream().map(a -> a.semanticTags.get(0)).toList());
        assertEquals("tumors", results.byTerm.get("tumours").get(0).mappingProvenance.get(0).input);

        repo.failing = Set.of("tumours", "tumour", "tumors");
        results = matcher.bulkTag(List.of("tumours"));
        assertEquals(Set.of("tumours"), results.failedTerms);
        assertFalse(results.byTerm.containsKey("tumours"));
    }
}
