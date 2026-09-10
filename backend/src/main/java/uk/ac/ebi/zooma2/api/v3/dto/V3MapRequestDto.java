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

    /**
     * When true, restrict results to the target ontologies' own namespaces,
     * excluding terms they merely import (default: false).
     */
    public Boolean definingOnly;

    /** Filter to restrict datasources. */
    public V3FilterDto filter;

    /** Term IDs to exclude from results (for "try again" / thumbs-down). */
    public List<String> excludeTermIds;

    /** When true, return all candidates with light deduplication only. */
    public Boolean returnAll;

    /**
     * When true, always run Phase 2 (deep embedding, cross-reference expansion).
     * When false, never run it. When unset (the default), Phase 2 runs only when
     * target ontologies are set and Phase 1 finds nothing from them that settles
     * the search (see {@code EscalationPolicy}); it is independent of
     * {@code returnAll}.
     */
    public Boolean deep;
}
