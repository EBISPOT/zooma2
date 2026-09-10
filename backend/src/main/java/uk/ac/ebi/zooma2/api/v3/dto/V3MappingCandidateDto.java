package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.ac.ebi.zooma2.model.MapResult;

/**
 * A single candidate ontology term mapping.
 */
public class V3MappingCandidateDto {

    /** The ontology term ID (e.g., "EFO:0000001"). */
    public String termId;

    /** The label of the ontology term. */
    public String label;

    /** The ontology this term belongs to (e.g., "efo", "mondo"). */
    public String ontology;

    /** Full IRI of the ontology term (e.g. "http://www.ebi.ac.uk/efo/EFO_0000001"). */
    public String uri;

    /** Confidence score (0.0 to 1.0). */
    public Double confidence;

    /** Source of this mapping (e.g., "atlas", "gwas", "ols"). */
    public String datasource;

    /**
     * {@code true} when the candidate's datasource or ontology is in the request's
     * {@code filter.preferred} list; such candidates are ranked ahead of equally
     * confident ones. Omitted otherwise.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean preferred;

    /** Provenance chain explaining how this mapping was established. */
    public List<V3MappingProvenanceStepDto> mappingProvenance;

    /**
     * Provenance chains of the other evidence channels that independently found
     * the same term (e.g. an embedding hit corroborating a label match). Each
     * such channel adds a small bonus to the confidence. Omitted when none.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<List<V3MappingProvenanceStepDto>> supportingProvenance;

    /**
     * Create a candidate from an internal MapResult.
     */
    public static V3MappingCandidateDto from(MapResult internal) {
        var dto = new V3MappingCandidateDto();
        dto.termId = internal.ontologyTermID;
        dto.label = internal.ontologyTermLabel;
        dto.ontology = internal.ontologyURI;
        dto.uri = internal.ontologyTermIri;
        dto.confidence = internal.mappingConfidence;
        dto.datasource = internal.datasource;
        dto.preferred = internal.preferred;
        dto.mappingProvenance = internal.mappingProvenance;
        dto.supportingProvenance = internal.supportingProvenance;
        return dto;
    }
}
