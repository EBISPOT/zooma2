package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

/**
 * Request body for the V3 mapping endpoint.
 * All parameters are in the body - no query parameters.
 */
public class V3MapRequestDto {

    /** List of properties to map to ontology terms. */
    public List<V3StringToMapDto> properties;

    /** Embedding model to use (default: text-embedding-3-small). */
    public String model;

    /** Preferred ontologies to prioritize in results. */
    public List<String> preferredOntologies;

    /** Filter to restrict datasources and ontologies. */
    public V3FilterDto filter;
}
