package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

/**
 * Request body for the annotate-text endpoint.
 * Accepts free text instead of a properties list.
 */
public class AnnotateTextRequestDto {

    /** The free text to segment and annotate. */
    public String text;

    /** Embedding model to use (default: text-embedding-3-small). */
    public String model;

    /** Target ontologies to prioritize in results. */
    public List<String> targetOntologies;

    /** When false, only return results from target ontologies (default: true). */
    public Boolean includeOtherOntologies;

    /** Filter to restrict datasources. */
    public V3FilterDto filter;

    /**
     * Rulesets to apply for this request, by id. When set, only the named rulesets
     * (and the rulesets they include) are applied; when omitted, all default
     * rulesets apply.
     */
    public List<String> ruleSets;
}
