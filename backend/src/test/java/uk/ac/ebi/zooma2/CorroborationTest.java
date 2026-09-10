package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingCandidateDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/** Several channels agreeing on a term corroborate it (issue #21). */
class CorroborationTest {

    private static final String A = "http://www.ebi.ac.uk/efo/EFO_0000001";
    private static final String B = "http://www.ebi.ac.uk/efo/EFO_0000002";

    private static Deduplicator dedup;

    @BeforeAll
    static void init() {
        dedup = new Deduplicator(new PrefixMap());
    }

    private static MapResult result(String iri, double confidence, String method, String matchType) {
        MapResult r = new MapResult();
        r.textToMap = "tumours";
        r.propertyType = "unspecified";
        r.ontologyTermIri = iri;
        r.ontologyTermID = iri.substring(iri.lastIndexOf('/') + 1);
        r.ontologyURI = "efo";
        r.datasource = "efo";
        r.mappingConfidence = confidence;
        var step = new V3MappingProvenanceStepDto();
        step.method = method;
        step.matchType = matchType;
        step.similarity = confidence;
        step.confidence = confidence;
        r.mappingProvenance = List.of(step);
        return r;
    }

    @Test
    void agreementRaisesTheBestResultAndKeepsTheOtherChainsAsSupport() {
        MapResult partial = result(A, 0.6, "lexical", "OLS_TEXT_TAGGER_SUBSTRING");
        MapResult embedding = result(A, 0.5, "semantic", "OLS_EMBEDDING");
        List<MapResult> results = new ArrayList<>(List.of(embedding, partial));

        dedup.recordCorroboration(results);

        assertEquals(0.6 + Deduplicator.CORROBORATION_BONUS, partial.mappingConfidence, 1e-9);
        assertEquals(List.of(embedding.mappingProvenance), partial.supportingProvenance);
        assertEquals(0.5, embedding.mappingConfidence, 1e-9, "only the best result is credited");
        assertNull(embedding.supportingProvenance);
    }

    @Test
    void bonusNeverExceedsTheEvidenceTiersCeiling() {
        MapResult label = result(A, 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult embedding = result(A, 0.85, "semantic", "OLS_EMBEDDING");
        List<MapResult> results = new ArrayList<>(List.of(label, embedding));
        dedup.recordCorroboration(results);
        assertEquals(1.0, label.mappingConfidence, 1e-9);
        assertEquals(1, label.supportingProvenance.size());

        MapResult strongEmbedding = result(B, 0.88, "semantic", "OLS_EMBEDDING");
        MapResult partial = result(B, 0.6, "lexical", "OLS_TEXT_TAGGER_SUBSTRING");
        results = new ArrayList<>(List.of(strongEmbedding, partial));
        dedup.recordCorroboration(results);
        assertEquals(0.89, strongEmbedding.mappingConfidence, 1e-9, "an embedding guess cannot become a full match");
    }

    @Test
    void noiseDoesNotCorroborate() {
        // "left tibia": an embedding guess at 0.795 and a fuzzy hit on the same SNOMED
        // term at 0.175 similarity; the bonus would have lifted the guess over the
        // weak-result gap and into the results.
        MapResult guess = result(B, 0.795, "semantic", "OLS_EMBEDDING");
        MapResult noise = result(B, 0.175, "lexical", "OLS_LEXICAL_FUZZY");
        List<MapResult> results = new ArrayList<>(List.of(guess, noise));

        dedup.recordCorroboration(results);

        assertEquals(0.795, guess.mappingConfidence, 1e-9);
        assertNull(guess.supportingProvenance);
    }

    @Test
    void theSameChannelTwiceIsNotCorroboration() {
        MapResult fromEfoFile = result(A, 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult fromMondoFile = result(A, 1.0, "lexical", "OLS_TEXT_TAGGER");
        fromMondoFile.ontologyURI = "mondo";
        List<MapResult> results = new ArrayList<>(List.of(fromEfoFile, fromMondoFile));

        dedup.recordCorroboration(results);

        assertEquals(1.0, fromEfoFile.mappingConfidence, 1e-9);
        assertNull(fromEfoFile.supportingProvenance);
        assertNull(fromMondoFile.supportingProvenance);
    }

    @Test
    void eachDistinctChannelAddsOneBonus() {
        MapResult variantLabel = result(A, 0.95, "lexical", "OLS_TEXT_TAGGER");
        MapResult fuzzy = result(A, 0.8, "lexical", "OLS_LEXICAL_FUZZY_LABEL");
        MapResult embedding = result(A, 0.85, "semantic", "OLS_EMBEDDING");
        List<MapResult> results = new ArrayList<>(List.of(fuzzy, embedding, variantLabel));

        dedup.recordCorroboration(results);

        assertEquals(0.99, variantLabel.mappingConfidence, 1e-9);
        assertEquals(2, variantLabel.supportingProvenance.size());
    }

    @Test
    void supportSurvivesTheEmbeddingRulesAndReachesTheCandidate() {
        MapResult label = result(A, 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult embeddingA = result(A, 0.85, "semantic", "OLS_EMBEDDING");
        MapResult embeddingB = result(B, 0.8, "semantic", "OLS_EMBEDDING");
        MapResult warning = MapResult.warning("tumours", "unspecified", "OLS was slow");

        var out = dedup.deduplicate(new ArrayList<>(List.of(embeddingB, embeddingA, label, warning)), Filter.fromLists(null, null, null, true));

        assertEquals(2, out.size(), out.toString());
        assertEquals(A, out.get(0).ontologyTermIri);
        assertEquals(1, out.get(0).supportingProvenance.size(), "the embedding twin was credited before the embedding rules removed it");
        assertEquals("OLS_EMBEDDING", out.get(0).supportingProvenance.get(0).get(0).matchType);
        assertEquals(warning, out.get(1));

        var dto = V3MappingCandidateDto.from(out.get(0));
        assertEquals(out.get(0).supportingProvenance, dto.supportingProvenance);
        assertNull(V3MappingCandidateDto.from(embeddingB).supportingProvenance, "omitted when there is none");
    }
}
