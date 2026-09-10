package uk.ac.ebi.zooma2.api.v2.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

class V2ConfidenceLevelTest {

    private static List<V3MappingProvenanceStepDto> prov(String method, String matchType) {
        var s = new V3MappingProvenanceStepDto();
        s.method = method;
        s.matchType = matchType;
        return List.of(s);
    }

    @Test
    void scoreThresholdsAreUnchangedForNonFullMatches() {
        assertEquals("HIGH", V2ConfidenceLevel.of(0.95, prov("semantic", "OLS_EMBEDDING")));
        assertEquals("GOOD", V2ConfidenceLevel.of(0.75, prov("semantic", "OLS_EMBEDDING")));
        assertEquals("MEDIUM", V2ConfidenceLevel.of(0.55, prov("lexical", "OLS_LEXICAL_FUZZY")));
        assertEquals("LOW", V2ConfidenceLevel.of(0.2, prov("lexical", "OLS_TEXT_TAGGER_SUBSTRING")));
        assertEquals("LOW", V2ConfidenceLevel.of(0.2, null));
    }

    @Test
    void fullMatchesAreHighEvenWhenRankingAdjustedTheScore() {
        // e.g. a synonym full match demoted by the organism/taxonomy preference
        assertEquals("HIGH", V2ConfidenceLevel.of(0.8, prov("lexical", "OLS_TEXT_TAGGER_SYNONYM")));
        assertEquals("HIGH", V2ConfidenceLevel.of(0.85, prov("curated", "CURATED_EXACT")));
        assertEquals("HIGH", V2ConfidenceLevel.of(1.0, prov("lexical", "OLS_LEXICAL_FUZZY_LABEL")));
    }

    @Test
    void curatedSubstringIsNotAFullMatch() {
        assertEquals("MEDIUM", V2ConfidenceLevel.of(0.5, prov("curated", "CURATED_SUBSTRING")));
    }
}
