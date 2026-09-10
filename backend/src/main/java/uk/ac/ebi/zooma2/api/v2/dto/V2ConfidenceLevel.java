package uk.ac.ebi.zooma2.api.v2.dto;

import java.util.List;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.matcher.EvidenceTier;

/**
 * The legacy V2 confidence buckets (HIGH / GOOD / MEDIUM / LOW), derived in one
 * place for both V2 DTOs.
 *
 * <p>A full match (curated, label or synonym, see {@link EvidenceTier}) is always
 * HIGH regardless of the numeric score: ranking rules may nudge a score up or
 * down to order candidates, but that must not turn a whole-text match into a
 * "GOOD" guess. Everything else falls through to the historical score thresholds.
 */
public final class V2ConfidenceLevel {

    private V2ConfidenceLevel() {
    }

    public static String of(double score, List<V3MappingProvenanceStepDto> provenance) {
        if (EvidenceTier.isFullMatch(provenance)) return "HIGH";
        return ofScore(score);
    }

    static String ofScore(double score) {
        if (score >= 0.9) return "HIGH";
        if (score >= 0.7) return "GOOD";
        if (score >= 0.5) return "MEDIUM";
        return "LOW";
    }
}
