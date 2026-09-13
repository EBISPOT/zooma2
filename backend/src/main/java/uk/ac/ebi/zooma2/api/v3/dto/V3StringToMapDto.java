package uk.ac.ebi.zooma2.api.v3.dto;

import uk.ac.ebi.zooma2.model.StringToMap;

/** One string to map, with the optional type of property it is the value of (e.g. "organism part"). */
public class V3StringToMapDto {

    public String propertyType;
    public String textToMap;

    /**
     * Convert this DTO to the internal StringToMap model.
     */
    public StringToMap toStringToMap() {
        var internal = new StringToMap();
        internal.propertyType = this.propertyType;
        internal.textToMap = this.textToMap;
        return internal;
    }
}
