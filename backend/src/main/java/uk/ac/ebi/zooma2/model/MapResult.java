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
    /** Full IRI of the term; the key results are deduplicated and excluded by. */
    public String ontologyTermIri;
    public String ontologyURI;
    public String datasource;
    public List<V3MappingProvenanceStepDto> mappingProvenance;
    /** {@code true} when the result's datasource or ontology is one the caller listed as preferred; otherwise unset. */
    public Boolean preferred;
    public String error;

    /**
     * A message for an error result that keeps the exception's identity: many
     * runtime exceptions (NPE, class casts) carry no message at all, and an
     * empty error string tells the caller nothing.
     */
    public static String describe(Throwable e) {
        if (e == null) return "unknown error";
        String name = e.getClass().getSimpleName();
        String message = e.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    public static MapResult error(String textToMap, String propertyType, String errorMessage) {
        MapResult r = new MapResult();
        r.textToMap = textToMap;
        r.propertyType = propertyType;
        r.error = errorMessage;
        return r;
    }

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
               Objects.equals(ontologyTermIri, that.ontologyTermIri) &&
               Objects.equals(ontologyURI, that.ontologyURI) &&
               Objects.equals(datasource, that.datasource) &&
               Objects.equals(preferred, that.preferred) &&
               Objects.equals(mappingProvenance, that.mappingProvenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyType, textToMap, ontologyTermLabel,
                            ontologyTermSynonyms, Double.valueOf(mappingConfidence),
                            ontologyTermID, ontologyTermIri, ontologyURI, datasource, preferred, mappingProvenance);
    }
}
