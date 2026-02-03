package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

/**
 * Response for the V3 mapping endpoint.
 * Results are grouped by input property.
 */
public class V3MapResponseDto {

    /** Mappings grouped by input property. */
    public List<V3PropertyMappingDto> mappings;

    public static V3MapResponseDto of(List<V3PropertyMappingDto> mappings) {
        var response = new V3MapResponseDto();
        response.mappings = mappings;
        return response;
    }
}
