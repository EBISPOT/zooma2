package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.MapResult;

/** One tier table for all matchers: same evidence, same score, wherever it was found. */
class EvidenceTierTest {

    private static V3MappingProvenanceStepDto step(String method, String matchType, Double similarity) {
        var s = new V3MappingProvenanceStepDto();
        s.method = method;
        s.matchType = matchType;
        s.similarity = similarity;
        s.confidence = similarity;
        return s;
    }

    @Test
    void matchTypesMapToTiers() {
        assertEquals(EvidenceTier.CURATED_FULL, EvidenceTier.ofMatchType("CURATED_EXACT"));
        assertEquals(EvidenceTier.LABEL_FULL, EvidenceTier.ofMatchType("OLS_TEXT_TAGGER"));
        assertEquals(EvidenceTier.LABEL_FULL, EvidenceTier.ofMatchType("OLS_LEXICAL_FUZZY_LABEL"));
        assertEquals(EvidenceTier.SYNONYM_FULL, EvidenceTier.ofMatchType("OLS_TEXT_TAGGER_SYNONYM"));
        assertEquals(EvidenceTier.SYNONYM_FULL, EvidenceTier.ofMatchType("OLS_LEXICAL_FUZZY_SYNONYM"));
        assertEquals(EvidenceTier.PARTIAL, EvidenceTier.ofMatchType("CURATED_SUBSTRING"));
        assertEquals(EvidenceTier.PARTIAL, EvidenceTier.ofMatchType("OLS_TEXT_TAGGER_SUBSTRING"));
        assertEquals(EvidenceTier.PARTIAL, EvidenceTier.ofMatchType("OLS_LEXICAL_FUZZY"));
        assertEquals(EvidenceTier.EMBEDDING, EvidenceTier.ofMatchType("OLS_EMBEDDING"));
        assertEquals(EvidenceTier.NONE, EvidenceTier.ofMatchType("OXO_MAPPING"));
        assertEquals(EvidenceTier.NONE, EvidenceTier.ofMatchType(null));
    }

    @Test
    void sameEvidenceScoresTheSameWhicheverMatcherFoundIt() {
        assertEquals(1.0, EvidenceTier.ofMatchType("OLS_TEXT_TAGGER").confidence(1.0));
        assertEquals(1.0, EvidenceTier.ofMatchType("OLS_LEXICAL_FUZZY_LABEL").confidence(1.0));
        assertEquals(0.9, EvidenceTier.ofMatchType("OLS_TEXT_TAGGER_SYNONYM").confidence(1.0));
        assertEquals(0.9, EvidenceTier.ofMatchType("OLS_LEXICAL_FUZZY_SYNONYM").confidence(1.0));
    }

    @Test
    void trustOrderIsCuratedLabelSynonymPartialEmbedding() {
        assertTrue(EvidenceTier.CURATED_FULL.rank() < EvidenceTier.LABEL_FULL.rank());
        assertTrue(EvidenceTier.LABEL_FULL.rank() < EvidenceTier.SYNONYM_FULL.rank());
        assertTrue(EvidenceTier.SYNONYM_FULL.rank() < EvidenceTier.PARTIAL.rank());
        assertTrue(EvidenceTier.PARTIAL.rank() < EvidenceTier.EMBEDDING.rank());
        // Curated full is no longer outranked by a plain label match: equal score, better tier
        assertEquals(EvidenceTier.LABEL_FULL.confidence(1.0), EvidenceTier.CURATED_FULL.confidence(1.0));
    }

    @Test
    void partialAndEmbeddingScaleBySimilarityUnderTheirCap() {
        assertEquals(0.5 * 0.89, EvidenceTier.PARTIAL.confidence(0.5), 1e-9);
        assertEquals(0.95 * 0.89, EvidenceTier.EMBEDDING.confidence(0.95), 1e-9);
        assertTrue(EvidenceTier.EMBEDDING.confidence(1.0) < EvidenceTier.SYNONYM_FULL.confidence(1.0));
    }

    @Test
    void fullMatchAndDefinitiveFlags() {
        assertTrue(EvidenceTier.CURATED_FULL.isFullMatch());
        assertTrue(EvidenceTier.SYNONYM_FULL.isFullMatch());
        assertFalse(EvidenceTier.PARTIAL.isFullMatch());
        assertFalse(EvidenceTier.EMBEDDING.isFullMatch());
        assertTrue(EvidenceTier.CURATED_FULL.isDefinitive(), "a curated full match must short-circuit like a label match");
        assertTrue(EvidenceTier.LABEL_FULL.isDefinitive());
        assertFalse(EvidenceTier.SYNONYM_FULL.isDefinitive());
    }

    @Test
    void tierOfAResultComesFromItsFirstStep() {
        List<V3MappingProvenanceStepDto> chain = new ArrayList<>();
        chain.add(step("curated", "CURATED_EXACT", 1.0));
        chain.add(V3MappingProvenanceStepDto.obsoleteReplacement("a", "A", "b", "B", "efo"));
        assertEquals(EvidenceTier.CURATED_FULL, EvidenceTier.of(chain));
        assertEquals(EvidenceTier.NONE, EvidenceTier.of(List.of()));
        assertEquals(EvidenceTier.NONE, EvidenceTier.of((List<V3MappingProvenanceStepDto>) null));
    }

    @Test
    void unknownMatchTypeFallsBackToMethodAndCoverage() {
        assertEquals(EvidenceTier.CURATED_FULL, EvidenceTier.of(step("curated", "ZOOMA_CURATED", 1.0)));
        assertEquals(EvidenceTier.PARTIAL, EvidenceTier.of(step("curated", "ZOOMA_CURATED", 0.4)));
        assertEquals(EvidenceTier.EMBEDDING, EvidenceTier.of(step("semantic", "SOMETHING_NEW", 0.8)));
    }

    @Test
    void embeddingScoreNeverCountsAsLexicallyGrounded() {
        assertFalse(EvidenceTier.isLexicallyGrounded(List.of(step("semantic", "OLS_EMBEDDING", 0.98))));
        assertFalse(EvidenceTier.isLexicallyGrounded(List.of(step("lexical", "OLS_TEXT_TAGGER_SUBSTRING", 0.6))));
        assertTrue(EvidenceTier.isLexicallyGrounded(List.of(step("curated", "CURATED_EXACT", 1.0))));
        assertTrue(EvidenceTier.isLexicallyGrounded(List.of(step("lexical", "OLS_LEXICAL_FUZZY_SYNONYM", 1.0))));
    }

    @Test
    void rankingBreaksConfidenceTiesByTier() {
        MapResult curated = new MapResult();
        curated.mappingConfidence = 1.0;
        curated.mappingProvenance = List.of(step("curated", "CURATED_EXACT", 1.0));
        MapResult label = new MapResult();
        label.mappingConfidence = 1.0;
        label.mappingProvenance = List.of(step("lexical", "OLS_TEXT_TAGGER", 1.0));
        MapResult embedding = new MapResult();
        embedding.mappingConfidence = 0.85;
        embedding.mappingProvenance = List.of(step("semantic", "OLS_EMBEDDING", 0.95));

        List<MapResult> sorted = new ArrayList<>(List.of(embedding, label, curated));
        sorted.sort(EvidenceTier.resultRanking());
        assertEquals(List.of(curated, label, embedding), sorted);
    }

    @Test
    void withinATierTheTaggerReportOutranksTheFuzzyEndpointReport() {
        MapResult viaTagger = new MapResult();
        viaTagger.mappingConfidence = 1.0;
        viaTagger.mappingProvenance = List.of(step("lexical", "OLS_TEXT_TAGGER", 1.0));
        MapResult viaFuzzy = new MapResult();
        viaFuzzy.mappingConfidence = 1.0;
        viaFuzzy.mappingProvenance = List.of(step("lexical", "OLS_LEXICAL_FUZZY_LABEL", 1.0));

        List<MapResult> sorted = new ArrayList<>(List.of(viaFuzzy, viaTagger));
        sorted.sort(EvidenceTier.resultRanking());
        assertEquals(List.of(viaTagger, viaFuzzy), sorted);
        // but never across tiers: a fuzzy-endpoint label match still beats a tagger synonym match on rank
        assertTrue(EvidenceTier.rankOf(viaFuzzy.mappingProvenance)
            < EvidenceTier.rankOf(List.of(step("lexical", "OLS_TEXT_TAGGER_SYNONYM", 1.0))));
    }
}
