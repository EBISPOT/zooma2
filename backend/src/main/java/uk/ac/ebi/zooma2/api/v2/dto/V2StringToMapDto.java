package uk.ac.ebi.zooma2.api.v2.dto;

import uk.ac.ebi.zooma2.model.StringToMap;

public class V2StringToMapDto {

    public String propertyType;
    public String propertyValue;

    /**
     * Convert this DTO to the internal StringToMap model.
     */
    public StringToMap toStringToMap() {
        var internal = new StringToMap();
        internal.propertyType = this.propertyType;
        internal.propertyValue = this.propertyValue;
        return internal;
    }
}
