package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;
import uk.ac.ebi.zooma2.model.Filter;

/**
 * Filter to restrict which datasources and ontologies are searched.
 * This is a simple JSON object - no string parsing needed.
 */
public class V3FilterDto {

    /** Required datasources - results must come from one of these. */
    public List<String> required;

    /** Preferred datasources - results from these are ranked higher. */
    public List<String> preferred;

    /** Ontologies to search (empty = all). */
    public List<String> ontologies;

    /**
     * Convert this DTO to the internal Filter model.
     */
    public Filter toFilter() {
        return Filter.fromLists(
            required != null ? required : List.of(),
            preferred != null ? preferred : List.of(),
            ontologies != null ? ontologies : List.of()
        );
    }
}
