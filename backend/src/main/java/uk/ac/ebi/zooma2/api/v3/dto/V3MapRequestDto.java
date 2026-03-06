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

    /** Target ontologies to prioritize in results. */
    public List<String> targetOntologies;

    /** When false, only return results from target ontologies (default: true). */
    public Boolean includeOtherOntologies;

    /** Filter to restrict datasources. */
    public V3FilterDto filter;

    /** Term IDs to exclude from results (for "try again" / thumbs-down). */
    public List<String> excludeTermIds;

    /** When true, return all candidates with light deduplication only. */
    public Boolean returnAll;
}
