package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

/**
 * Response for the V3 mapping endpoint.
 * Results are grouped by input string.
 */
public class V3MapResponseDto {

    /** Mappings grouped by input string. */
    public List<V3StringMappingDto> mappings;

    public static V3MapResponseDto of(List<V3StringMappingDto> mappings) {
        var response = new V3MapResponseDto();
        response.mappings = mappings;
        return response;
    }
}
