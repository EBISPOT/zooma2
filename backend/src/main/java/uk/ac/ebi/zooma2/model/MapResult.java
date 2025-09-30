package uk.ac.ebi.zooma2.model;

import java.util.Objects;

public class MapResult {

    public String propertyType;
    public String propertyValue;
    public String ontologyTermLabel;
    public String ontologyTermSynonyms;
    public String mappingConfidence;
    public String ontologyTermID;
    public String ontologyURI;
    public String datasource;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MapResult that = (MapResult) o;

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
