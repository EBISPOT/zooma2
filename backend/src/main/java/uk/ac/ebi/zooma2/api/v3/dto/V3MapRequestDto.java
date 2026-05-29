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

    /**
     * When true, always run Phase 2 (deep embedding, cross-reference expansion).
     * When false, skip Phase 2 even if target ontologies are not found in Phase 1.
     * Defaults to the value of {@code returnAll} if not set.
     */
    public Boolean deep;

    /**
     * When true, deduplication keeps only the single best result per target
     * ontology. Opt-in: when null or false, the full candidate set (one per
     * unique term) is returned, which is the right default for callers that
     * re-rank downstream.
     */
    public Boolean limitPerOntology;

    /**
     * Rulesets to apply for this request, by id (e.g. {@code ["gwas-catalog"]}).
     * When set, only the named rulesets (and the rulesets they {@code include})
     * are applied. When omitted, all default rulesets apply.
     */
    public List<String> ruleSets;
}
