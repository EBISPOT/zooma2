package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.ac.ebi.zooma2.matcher.EvidenceTier;
import uk.ac.ebi.zooma2.model.MapResult;

/**
 * Mapping results for one input string.
 */
public class V3StringMappingDto {

    /** The property type from the input. */
    public String propertyType;

    /** The string from the input. */
    public String textToMap;

    /** Candidate ontology term mappings, ranked by confidence. */
    public List<V3MappingCandidateDto> candidates;

    /** Error message if mapping failed for this string. */
    public String error;

    /**
     * Degradations that did not fail the mapping but may have cost candidates:
     * an OLS endpoint unavailable, terms that could not be resolved. Absent
     * when the mapping ran cleanly. A string with warnings and no candidates
     * should not be taken as "nothing matches".
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> warnings;

    /** {@code true} when the per-string time budget ran out and the results may be incomplete; omitted otherwise. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean truncated;

    public static V3StringMappingDto of(String propertyType, String textToMap, List<V3MappingCandidateDto> candidates) {
        var dto = new V3StringMappingDto();
        dto.propertyType = propertyType;
        dto.textToMap = textToMap;
        dto.candidates = candidates;
        return dto;
    }

    /** Candidates (ranked), first error and distinct warnings from one string's deduplicated results. */
    public static V3StringMappingDto fromResults(String propertyType, String textToMap, List<MapResult> results) {
        var dto = new V3StringMappingDto();
        dto.propertyType = propertyType;
        dto.textToMap = textToMap;
        dto.candidates = results.stream()
            .filter(r -> !r.isDiagnostic())
            .map(V3MappingCandidateDto::from)
            .sorted(EvidenceTier.candidateRanking())
            .collect(Collectors.toList());
        dto.error = results.stream().filter(r -> r.error != null).map(r -> r.error).findFirst().orElse(null);
        List<String> warnings = results.stream().filter(r -> r.warning != null).map(r -> r.warning).distinct().collect(Collectors.toList());
        dto.warnings = warnings.isEmpty() ? null : warnings;
        dto.truncated = warnings.stream().anyMatch(w -> w.startsWith(uk.ac.ebi.zooma2.mapping.StringMapper.TRUNCATION_WARNING_PREFIX)) ? Boolean.TRUE : null;
        return dto;
    }
}
