package uk.ac.ebi.zooma2.api.v3.dto;

/**
 * Represents a single step in the provenance chain explaining how a mapping was established.
 * Multiple steps form a chain where each step's target can be the next step's input.
 */
public class V3MappingProvenanceStepDto {

    /** The method used for this step: "lexical", "semantic", "curated", "cross_reference" */
    public String method;

    /** Specific match type: "exact_label", "synonym", "embedding_similarity", "skos:exactMatch", etc. */
    public String matchType;

    /** Source of this step: "atlas", "gwas", "ols", etc. */
    public String source;

    /** Embedding model used (for semantic matches). */
    public String model;

    /** Confidence score for this step (0.0 to 1.0). */
    public Double confidence;

    /** The input to this step (search term or term ID from previous step). */
    public String input;

    /** The text that matched (if different from input). */
    public String matchedText;

    /** Similarity score. For semantic matches this is the embedding similarity; for lexical matches this reflects exact/synonym match (e.g., 1.0 for exact, 0.9 for synonym). */
    public Double similarity;

    /** The term ID reached by this step. */
    public String target;

    public static V3MappingProvenanceStepDto lexical(String source, String matchType, String input, String matchedText, String target, Double similarity) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "lexical";
        step.matchType = matchType;
        step.source = source;
        step.input = input;
        step.matchedText = matchedText;
        step.target = target;
        // For lexical matches, populate both similarity and confidence with the provided score
        step.similarity = similarity;
        step.confidence = similarity;
        return step;
    }

    public static V3MappingProvenanceStepDto semantic(String source, String model, String input, String matchedText, String target, Double similarity, Double confidence) {
        return semantic(source, model, input, matchedText, target, similarity, confidence, "ZOOMA_LLM_EMBEDDING_OF_CURATED");
    }

    public static V3MappingProvenanceStepDto semantic(String source, String model, String input, String matchedText, String target, Double similarity, Double confidence, String matchType) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "semantic";
        step.matchType = matchType;
        step.source = source;
        step.model = model;
        step.input = input;
        step.matchedText = matchedText;
        step.target = target;
        step.similarity = similarity;
        step.confidence = confidence;
        return step;
    }

    public static V3MappingProvenanceStepDto curated(String source, String input, String target, Double confidence) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "curated";
        step.matchType = "ZOOMA_CURATED";
        step.source = source;
        step.input = input;
        step.target = target;
        step.confidence = confidence;
        return step;
    }

    public static V3MappingProvenanceStepDto curated(String source, String matchType, String input, String matchedText, String target, Double similarity) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "curated";
        step.matchType = matchType;
        step.source = source;
        step.input = input;
        step.matchedText = matchedText;
        step.target = target;
        step.similarity = similarity;
        return step;
    }

    /**
     * Create a provenance step for obsolete term replacement.
     * @param obsoleteTermIri The IRI of the obsolete term
     * @param obsoleteTermLabel The label of the obsolete term
     * @param replacementTermIri The IRI of the replacement term
     * @param replacementTermLabel The label of the replacement term
     * @param ontology The ontology containing the terms
     */
    public static V3MappingProvenanceStepDto obsoleteReplacement(
            String obsoleteTermIri, String obsoleteTermLabel,
            String replacementTermIri, String replacementTermLabel,
            String ontology) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "cross_reference";
        step.matchType = "OBSOLETE_REPLACEMENT";
        step.source = "ols:" + ontology;
        step.input = obsoleteTermIri;
        step.matchedText = obsoleteTermLabel + " → " + replacementTermLabel;
        step.target = replacementTermIri;
        step.confidence = 1.0;
        return step;
    }

    /**
     * Create a provenance step for traversing up the ontology hierarchy to find a superclass.
     * Uses rdfs:subClassOf relationships only.
     * @param childTermIri The IRI of the child/specific term
     * @param childTermLabel The label of the child term
     * @param parentTermIri The IRI of the parent/superclass term
     * @param parentTermLabel The label of the parent term
     * @param ontology The ontology containing the terms
     */
    public static V3MappingProvenanceStepDto superclass(
            String childTermIri, String childTermLabel,
            String parentTermIri, String parentTermLabel,
            String ontology) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "ontology_traversal";
        step.matchType = "OLS_SUPERCLASS";
        step.source = "ols:" + ontology;
        step.input = childTermIri;
        step.matchedText = childTermLabel + " ⊂ " + parentTermLabel;
        step.target = parentTermIri;
        step.confidence = 1.0;
        return step;
    }

    /**
     * Create a provenance step for traversing up the ontology hierarchy using hierarchical relationships.
     * Includes part-of and other hierarchical relationships beyond just rdfs:subClassOf.
     * @param childTermIri The IRI of the child/specific term
     * @param childTermLabel The label of the child term
     * @param ancestorTermIri The IRI of the ancestor term
     * @param ancestorTermLabel The label of the ancestor term
     * @param ontology The ontology containing the terms
     */
    public static V3MappingProvenanceStepDto hierarchicalAncestor(
            String childTermIri, String childTermLabel,
            String ancestorTermIri, String ancestorTermLabel,
            String ontology) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "ontology_traversal";
        step.matchType = "OLS_HIERARCHICAL_ANCESTOR";
        step.source = "ols:" + ontology;
        step.input = childTermIri;
        step.matchedText = childTermLabel + " ⊑ " + ancestorTermLabel;
        step.target = ancestorTermIri;
        step.confidence = 1.0;
        return step;
    }

    /**
     * Create a provenance step for OXO cross-reference mapping.
     * @param sourceId The source term ID (e.g., "DOID:162")
     * @param sourceLabel The label of the source term
     * @param targetId The target term ID (e.g., "MONDO:0005015")
     * @param targetLabel The label of the target term
     * @param distance The OXO mapping distance (1 = direct, 2 = one hop, etc.)
     */
    public static V3MappingProvenanceStepDto oxoMapping(
            String sourceId, String sourceLabel,
            String targetId, String targetLabel,
            int distance) {
        var step = new V3MappingProvenanceStepDto();
        step.method = "cross_reference";
        step.matchType = "OXO_MAPPING";
        step.source = "oxo";
        step.input = sourceId;
        step.matchedText = sourceLabel + " ≈ " + targetLabel + " (distance=" + distance + ")";
        step.target = targetId;
        // Confidence decreases with distance: distance 1 = 0.95, 2 = 0.85, 3+ = 0.75
        step.confidence = distance == 1 ? 0.95 : (distance == 2 ? 0.85 : 0.75);
        return step;
    }
}
