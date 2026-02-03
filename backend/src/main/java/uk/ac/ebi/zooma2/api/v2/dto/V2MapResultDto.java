package uk.ac.ebi.zooma2.api.v2.dto;

import java.util.Objects;
import uk.ac.ebi.zooma2.model.MapResult;

public class V2MapResultDto {

    public String propertyType;
    public String propertyValue;
    public String ontologyTermLabel;
    public String ontologyTermSynonyms;
    public String mappingConfidence;
    public String ontologyTermID;
    public String ontologyURI;
    public String datasource;

    /**
     * Create a DTO from the internal MapResult model.
     */
    public static V2MapResultDto from(MapResult internal) {
        V2MapResultDto dto = new V2MapResultDto();
        dto.propertyType = internal.propertyType;
        dto.propertyValue = internal.propertyValue;
        dto.ontologyTermLabel = internal.ontologyTermLabel;
        dto.ontologyTermSynonyms = internal.ontologyTermSynonyms;
        dto.mappingConfidence = internal.mappingConfidence;
        dto.ontologyTermID = internal.ontologyTermID;
        dto.ontologyURI = internal.ontologyURI;
        dto.datasource = internal.datasource;
        return dto;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        V2MapResultDto that = (V2MapResultDto) o;

        return Objects.equals(propertyType, that.propertyType) &&
               Objects.equals(propertyValue, that.propertyValue) &&
               Objects.equals(ontologyTermLabel, that.ontologyTermLabel) &&
               Objects.equals(ontologyTermSynonyms, that.ontologyTermSynonyms) &&
               Objects.equals(mappingConfidence, that.mappingConfidence) &&
               Objects.equals(ontologyTermID, that.ontologyTermID) &&
               Objects.equals(ontologyURI, that.ontologyURI) &&
               Objects.equals(datasource, that.datasource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyType, propertyValue, ontologyTermLabel,
                            ontologyTermSynonyms, mappingConfidence,
                            ontologyTermID, ontologyURI, datasource);
    }
}
