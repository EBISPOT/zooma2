package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;
import uk.ac.ebi.zooma2.model.MapResult;

/**
 * A single candidate ontology term mapping.
 */
public class V3MappingCandidateDto {

    /** The ontology term ID (e.g., "EFO:0000001"). */
    public String termId;

    /** The label of the ontology term. */
    public String label;

    /** Synonyms of the ontology term. */
    public List<String> synonyms;

    /** The ontology this term belongs to (e.g., "efo", "mondo"). */
    public String ontology;

    /** Full URI of the ontology term. */
    public String uri;

    /** Confidence score (0.0 to 1.0). */
    public Double confidence;

    /** Source of this mapping (e.g., "atlas", "gwas", "ols"). */
    public String datasource;

    /** Provenance chain explaining how this mapping was established. */
    public List<V3MappingProvenanceStepDto> mappingProvenance;

    /**
     * Create a candidate from an internal MapResult.
     */
    public static V3MappingCandidateDto from(MapResult internal) {
        var dto = new V3MappingCandidateDto();
        dto.termId = internal.ontologyTermID;
        dto.label = internal.ontologyTermLabel;
        dto.synonyms = parseSynonyms(internal.ontologyTermSynonyms);
        dto.ontology = internal.ontologyURI;
        dto.uri = internal.ontologyURI;
        dto.confidence = parseConfidence(internal.mappingConfidence);
        dto.datasource = internal.datasource;
        dto.mappingProvenance = internal.mappingProvenance;
        return dto;
    }

    private static List<String> parseSynonyms(String synonymsStr) {
        if (synonymsStr == null || synonymsStr.isBlank()) {
            return List.of();
        }
        return List.of(synonymsStr.split("\\|"));
    }

    private static Double parseConfidence(String confidenceStr) {
        if (confidenceStr == null || confidenceStr.isBlank()) {
            return null;
        }
        try {
            // Handle confidence strings like "HIGH", "MEDIUM", "LOW"
            return switch (confidenceStr.toUpperCase()) {
                case "HIGH" -> 0.9;
                case "GOOD" -> 0.75;
                case "MEDIUM" -> 0.6;
                case "LOW" -> 0.3;
                default -> Double.parseDouble(confidenceStr);
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
