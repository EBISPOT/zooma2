package uk.ac.ebi.zooma2.model;

import java.util.List;
import java.util.Objects;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

public class MapResult {

    public String propertyType;
    public String textToMap;
    public String ontologyTermLabel;
    public String ontologyTermSynonyms;
    public double mappingConfidence;
    public String ontologyTermID;
    public String ontologyURI;
    public String datasource;
    public List<V3MappingProvenanceStepDto> mappingProvenance;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MapResult that = (MapResult) o;

        return Objects.equals(propertyType, that.propertyType) &&
               Objects.equals(textToMap, that.textToMap) &&
               Objects.equals(ontologyTermLabel, that.ontologyTermLabel) &&
               Objects.equals(ontologyTermSynonyms, that.ontologyTermSynonyms) &&
               mappingConfidence == that.mappingConfidence &&
               Objects.equals(ontologyTermID, that.ontologyTermID) &&
               Objects.equals(ontologyURI, that.ontologyURI) &&
               Objects.equals(datasource, that.datasource) &&
               Objects.equals(mappingProvenance, that.mappingProvenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyType, textToMap, ontologyTermLabel,
                            ontologyTermSynonyms, Double.valueOf(mappingConfidence),
                            ontologyTermID, ontologyURI, datasource, mappingProvenance);
    }
}
