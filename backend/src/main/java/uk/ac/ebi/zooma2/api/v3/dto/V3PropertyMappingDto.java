package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

/**
 * Mapping results for a single input property.
 */
public class V3PropertyMappingDto {

    /** The property type from the input. */
    public String propertyType;

    /** The property value from the input. */
    public String propertyValue;

    /** Candidate ontology term mappings, ranked by confidence. */
    public List<V3MappingCandidateDto> candidates;

    public static V3PropertyMappingDto of(String propertyType, String propertyValue, List<V3MappingCandidateDto> candidates) {
        var dto = new V3PropertyMappingDto();
        dto.propertyType = propertyType;
        dto.propertyValue = propertyValue;
        dto.candidates = candidates;
        return dto;
    }
}
