package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/**
 * Deduplicator rules that used to key on the reporting matcher or on a score
 * threshold now key on the evidence class (issue #10).
 */
class DeduplicatorEvidenceGatesTest {

    private static Deduplicator dedup;

    @BeforeAll
    static void init() {
        dedup = new Deduplicator(new PrefixMap()); // fetches the Bioregistry once
    }

    private static MapResult result(String termId, String ontology, double confidence, String method, String matchType, Double similarity) {
        MapResult r = new MapResult();
        r.textToMap = "rat";
        r.ontologyTermID = termId;
        r.ontologyURI = ontology;
        r.datasource = "curated".equals(method) ? "atlas" : ontology;
        r.mappingConfidence = confidence;
        var step = new V3MappingProvenanceStepDto();
        step.method = method;
        step.matchType = matchType;
        step.similarity = similarity;
        step.confidence = similarity;
        r.mappingProvenance = List.of(step);
        return r;
    }

    @Test
    void curatedSubstringNoLongerSuppressesEmbeddingResults() {
        MapResult curatedSubstring = result("EFO_0000001", "efo", 0.4, "curated", "CURATED_SUBSTRING", 0.45);
        MapResult embedding = result("EFO_0000002", "efo", 0.8, "semantic", "OLS_EMBEDDING", 0.9);
        List<MapResult> results = new ArrayList<>(List.of(curatedSubstring, embedding));

        dedup.suppressEmbeddingIfExactExists(results);

        assertEquals(2, results.size(), "a low-coverage curated hit must not delete every embedding candidate");
    }

    @Test
    void curatedFullMatchStillSuppressesEmbeddingResults() {
        MapResult curatedFull = result("EFO_0000001", "efo", 1.0, "curated", "CURATED_EXACT", 1.0);
        MapResult embedding = result("EFO_0000002", "efo", 0.8, "semantic", "OLS_EMBEDDING", 0.9);
        List<MapResult> results = new ArrayList<>(List.of(curatedFull, embedding));

        dedup.suppressEmbeddingIfExactExists(results);

        assertEquals(List.of(curatedFull), results);
    }

    @Test
    void requiredDatasourceFilterStillAppliesToCuratedSubstrings() {
        MapResult fromGwas = result("EFO_0000001", "efo", 0.4, "curated", "CURATED_SUBSTRING", 0.45);
        fromGwas.datasource = "gwas";
        MapResult fromAtlas = result("EFO_0000002", "efo", 0.4, "curated", "CURATED_SUBSTRING", 0.45);
        MapResult ontologyHit = result("EFO_0000003", "efo", 0.8, "semantic", "OLS_EMBEDDING", 0.9);
        List<MapResult> results = new ArrayList<>(List.of(fromGwas, fromAtlas, ontologyHit));

        dedup.filterByRequiredDatasources(results, Filter.fromLists(List.of("atlas"), null, null, true));

        assertEquals(List.of(fromAtlas, ontologyHit), results);
    }

    @Test
    void taxonomyBoostIgnoresHighScoringEmbeddingGuesses() {
        // An NCBITaxon embedding hit at 0.87 crossed the old 0.85 score gate
        MapResult taxonGuess = result("NCBITaxon_1234", "ncbitaxon", 0.87, "semantic", "OLS_EMBEDDING", 0.98);
        MapResult foodLabel = result("FOODON_0001", "foodon", 1.0, "lexical", "OLS_TEXT_TAGGER", 1.0);
        for (MapResult r : List.of(taxonGuess, foodLabel)) r.propertyType = "organism";
        List<MapResult> results = new ArrayList<>(List.of(taxonGuess, foodLabel));

        dedup.preferNamespacesForPropertyType(results);

        assertEquals(0.87, taxonGuess.mappingConfidence, 1e-9, "no boost without a lexically grounded taxon match");
        assertEquals(1.0, foodLabel.mappingConfidence, 1e-9, "no demotion either");
    }

    @Test
    void taxonomyBoostFiresOnCuratedOrLexicalFullMatches() {
        MapResult taxonSynonym = result("NCBITaxon_10116", "ncbitaxon", 0.9, "lexical", "OLS_TEXT_TAGGER_SYNONYM", 1.0);
        MapResult taxonGuess = result("NCBITaxon_1234", "ncbitaxon", 0.87, "semantic", "OLS_EMBEDDING", 0.98);
        MapResult foodLabel = result("FOODON_0001", "foodon", 1.0, "lexical", "OLS_TEXT_TAGGER", 1.0);
        for (MapResult r : List.of(taxonSynonym, taxonGuess, foodLabel)) r.propertyType = "organism";
        List<MapResult> results = new ArrayList<>(List.of(taxonSynonym, taxonGuess, foodLabel));

        dedup.preferNamespacesForPropertyType(results);

        assertEquals(1.0, taxonSynonym.mappingConfidence, 1e-9, "grounded taxon match boosted");
        assertEquals(0.87, taxonGuess.mappingConfidence, 1e-9, "embedding guess from NCBITaxon earns no boost");
        assertEquals(0.9, foodLabel.mappingConfidence, 1e-9, "other ontologies demoted");
    }

    @Test
    void dedupByTermIdPrefersStrongerEvidenceOnEqualConfidence() {
        MapResult label = result("EFO_0000001", "efo", 1.0, "lexical", "OLS_TEXT_TAGGER", 1.0);
        MapResult curated = result("EFO_0000001", "efo", 1.0, "curated", "CURATED_EXACT", 1.0);
        List<MapResult> deduped = dedup.deduplicateByTermId(new ArrayList<>(List.of(label, curated)));

        assertEquals(1, deduped.size());
        assertTrue(deduped.get(0) == curated, "curated full match reported, not whichever came first");
        assertFalse(Deduplicator.isCuratedExact(label));
        assertTrue(Deduplicator.isCuratedExact(curated));
    }
}
