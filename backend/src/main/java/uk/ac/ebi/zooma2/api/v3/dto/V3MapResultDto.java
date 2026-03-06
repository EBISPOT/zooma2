package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.Objects;
import uk.ac.ebi.zooma2.model.MapResult;

public class V3MapResultDto {

    public String propertyType;
    public String textToMap;
    public String ontologyTermLabel;
    public String ontologyTermSynonyms;
    public String mappingConfidence;
    public String ontologyTermID;
    public String ontologyURI;
    public String datasource;

    /**
     * Create a DTO from the internal MapResult model.
     */
    public static V3MapResultDto from(MapResult internal) {
        V3MapResultDto dto = new V3MapResultDto();
        dto.propertyType = internal.propertyType;
        dto.textToMap = internal.textToMap;
        dto.ontologyTermLabel = internal.ontologyTermLabel;
        dto.ontologyTermSynonyms = internal.ontologyTermSynonyms;
        dto.mappingConfidence = String.valueOf(internal.mappingConfidence);
        dto.ontologyTermID = internal.ontologyTermID;
        dto.ontologyURI = internal.ontologyURI;
        dto.datasource = internal.datasource;
        return dto;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        V3MapResultDto that = (V3MapResultDto) o;

        return Objects.equals(propertyType, that.propertyType) &&
               Objects.equals(textToMap, that.textToMap) &&
               Objects.equals(ontologyTermLabel, that.ontologyTermLabel) &&
               Objects.equals(ontologyTermSynonyms, that.ontologyTermSynonyms) &&
               Objects.equals(mappingConfidence, that.mappingConfidence) &&
               Objects.equals(ontologyTermID, that.ontologyTermID) &&
               Objects.equals(ontologyURI, that.ontologyURI) &&
               Objects.equals(datasource, that.datasource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyType, textToMap, ontologyTermLabel,
                            ontologyTermSynonyms, mappingConfidence,
                            ontologyTermID, ontologyURI, datasource);
    }
}
