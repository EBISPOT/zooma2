package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;
import uk.ac.ebi.zooma2.model.Filter;

/**
 * Filter to restrict which datasources are searched.
 * This is a simple JSON object - no string parsing needed.
 */
public class V3FilterDto {

    /** Required datasources - results must come from one of these. */
    public List<String> required;

    /**
     * Preferred sources: curated datasource names (e.g. "atlas", "gwas") or
     * ontology ids (e.g. "efo"). Candidates from these are flagged
     * {@code preferred} and ranked ahead of equally confident candidates; their
     * confidence scores are not changed.
     */
    public List<String> preferred;

    /**
     * Convert this DTO to the internal Filter model.
     * Target ontologies, includeOtherOntologies and definingOnly are set at the request level, not here.
     */
    public Filter toFilter(List<String> targetOntologies, boolean includeOtherOntologies, boolean definingOnly) {
        return Filter.fromLists(
            required != null ? required : List.of(),
            preferred != null ? preferred : List.of(),
            targetOntologies != null ? targetOntologies : List.of(),
            includeOtherOntologies,
            definingOnly
        );
    }
}
