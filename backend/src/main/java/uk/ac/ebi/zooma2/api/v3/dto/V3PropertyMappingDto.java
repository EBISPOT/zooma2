package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

/**
 * Mapping results for a single input property.
 */
public class V3PropertyMappingDto {

    /** The property type from the input. */
    public String propertyType;

    /** The property value from the input. */
    public String textToMap;

    /** Candidate ontology term mappings, ranked by confidence. */
    public List<V3MappingCandidateDto> candidates;

    /** Error message if mapping failed for this property. */
    public String error;

    public static V3PropertyMappingDto of(String propertyType, String textToMap, List<V3MappingCandidateDto> candidates) {
        var dto = new V3PropertyMappingDto();
        dto.propertyType = propertyType;
        dto.textToMap = textToMap;
        dto.candidates = candidates;
        return dto;
    }
}
