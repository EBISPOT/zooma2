package uk.ac.ebi.zooma2.matcher;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Matcher that uses OLS V2 embedding similarity API to find semantically similar terms.
 * Takes existing annotations and queries OLS for similar terms using embedding models.
 */
public class OlsEmbeddingSimilarMatcher implements AnnotationMatcher {
    private static final int DEFAULT_SIZE = 50;
    
    /** Discount for an indirect mapping: the seed's confidence scaled by this and by the similarity. */
    private static final double EXPANSION_DISCOUNT = 0.7;

    private final OlsClientRepo olsRepo;
    private final List<String> models;
    private final double minSimilarity;

    public OlsEmbeddingSimilarMatcher(OlsClientRepo olsRepo, List<String> models) {
        this(olsRepo, models, 0.7);
    }

    /**
     * @param minSimilarity similar classes below this embedding similarity are not
     *                      expanded to (the same threshold the embedding matcher uses)
     */
    public OlsEmbeddingSimilarMatcher(OlsClientRepo olsRepo, List<String> models, double minSimilarity) {
        this.olsRepo = olsRepo;
        this.models = models != null && !models.isEmpty() ? models : getDefaultModels();
        this.minSimilarity = minSimilarity;
    }

    private List<String> getDefaultModels() {
        return List.of("llama-embed-nemotron-8b_pca512");
    }

    @Override
    public String getName() {
        return "OLS Embedding Similar";
    }

    /**
     * Takes existing annotations and finds semantically similar terms using OLS embeddings.
     * Only runs if previousResults exist.
     * Filters out embedding-based matches to avoid redundancy.
     */
    @Override
    public List<Annotation> findMatches(MatchContext context) {
        // Only run if we have target ontologies specified
        if (context.targetOntologies == null || context.targetOntologies.isEmpty()) {
            return Collections.emptyList();
        }

        // Only run if we have previous results to expand
        if (context.previousResults == null || context.previousResults.isEmpty()) {
            return Collections.emptyList();
        }

        // Check if we already have results from target ontologies
        Set<String> preferredOntologiesLower = context.targetOntologies.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        
        boolean hasPreferredOntologyResults = context.previousResults.stream()
            .anyMatch(a -> {
                if (a.semanticTags == null || a.semanticTags.isEmpty()) {
                    return false;
                }
                if (a.provenance == null || a.provenance.source == null || a.provenance.source.name == null) {
                    return false;
                }
                String ontologyName = a.provenance.source.name.toLowerCase();
                return preferredOntologiesLower.contains(ontologyName);
            });
        
        if (hasPreferredOntologyResults) {
            System.err.println("OLS Embedding Similar: Skipping expansion - already have results from target ontologies: " + 
                context.targetOntologies);
            return Collections.emptyList();
        }

        System.err.println("OLS Embedding Similar: Attempting to find similar terms for " + 
            context.previousResults.size() + " results using models: " + models);

        List<Annotation> expandedAnnotations = new ArrayList<>();

        // Group annotations by their semantic tags for batch processing
        // Filter out embedding-based and semantic matches, and low confidence matches
        Map<String, List<Annotation>> annotationsByTag = context.previousResults.stream()
            .filter(a -> a.semanticTags != null && !a.semanticTags.isEmpty())
            .filter(a -> !isEmbeddingOrSemanticMatch(a))
            .filter(a -> getConfidenceScore(a) > 0.7)
            .collect(Collectors.groupingBy(a -> a.semanticTags.get(0), LinkedHashMap::new, Collectors.toList()));

        // Get unique term IRIs to query
        Set<String> termIris = annotationsByTag.keySet();

        System.err.println("OLS LLM Similar: Querying " + termIris.size() + " term IRIs");

        // Query OLS LLM similar API for each term IRI and model
        // Key: sourceIri|model, Value: list of similar OlsTerms (already resolved from the response)
        Map<String, List<OlsTerm>> similarTermsBySource = new LinkedHashMap<>();
        
        for (String termIri : termIris) {
            for (String model : models) {
                List<OlsTerm> similarTerms = olsRepo.findSimilarTerms(termIri, model, DEFAULT_SIZE);
                if (!similarTerms.isEmpty()) {
                    String key = termIri + "|" + model;
                    similarTermsBySource.put(key, similarTerms);
                }
            }
        }

        if (similarTermsBySource.isEmpty()) {
            System.err.println("OLS LLM Similar: Found no similar terms");
            return Collections.emptyList();
        }

        System.err.println("OLS LLM Similar: Found " + similarTermsBySource.size() + " term-model combinations");

        // Create new annotations for each similar term, filtering to target ontologies
        for (Map.Entry<String, List<OlsTerm>> entry : similarTermsBySource.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String sourceIri = parts[0];
            String model = parts[1];
            List<OlsTerm> similarTerms = entry.getValue();

            // Get the source annotations that have this semantic tag
            List<Annotation> sourceAnnotations = annotationsByTag.get(sourceIri);
            if (sourceAnnotations == null || sourceAnnotations.isEmpty()) {
                continue;
            }

            // Create expanded annotations for each similar term from a target ontology
            for (OlsTerm similarTerm : similarTerms) {
                if (similarTerm.iri == null || similarTerm.ontology_name == null) continue;
                if (!preferredOntologiesLower.contains(similarTerm.ontology_name.toLowerCase())) continue;
                if (similarTerm.iri.equals(sourceIri)) continue;
                // A near-identical class and a barely related one must not rank alike:
                // no score means no evidence, and a weak similarity is not an expansion.
                if (similarTerm.score == null || similarTerm.score < minSimilarity) continue;

                for (Annotation sourceAnnotation : sourceAnnotations) {
                    Annotation expandedAnnotation = createExpandedAnnotation(
                        sourceAnnotation,
                        sourceIri,
                        similarTerm,
                        model
                    );
                    expandedAnnotations.add(expandedAnnotation);
                }
            }
        }

        System.err.println("OLS LLM Similar: Created " + expandedAnnotations.size() + " expanded annotations");
        return expandedAnnotations;
    }

    /**
     * Check if annotation was created using embedding or semantic search.
     * These should not be expanded via LLM similar to avoid redundancy.
     */
    private boolean isEmbeddingOrSemanticMatch(Annotation annotation) {
        if (annotation.provenance == null || annotation.provenance.evidence == null) {
            return false;
        }
        String evidence = annotation.provenance.evidence;
        return evidence.equals("CURATED_EMBEDDING_MATCH") ||
               evidence.equals("PREFERRED_ONTOLOGY_EMBEDDING") ||
               evidence.equals("OLS_EMBEDDING");
    }

    /**
     * Get confidence as a score between 0 and 1.
     */
    private double getConfidenceScore(Annotation annotation) {
        return annotation.confidence;
    }

    /**
     * Create a new annotation based on an existing one, but with a different target term from LLM similarity.
     */
    private Annotation createExpandedAnnotation(
            Annotation sourceAnnotation,
            String sourceIri,
            OlsTerm similarTerm,
            String model) {
        
        Annotation expandedAnnotation = new Annotation();
        
        // Copy the annotated property
        expandedAnnotation.annotatedProperty = new Annotation.AnnotatedProperty();
        expandedAnnotation.annotatedProperty.propertyType = sourceAnnotation.annotatedProperty.propertyType;
        expandedAnnotation.annotatedProperty.propertyValue = sourceAnnotation.annotatedProperty.propertyValue;
        
        // Set the new semantic tag to the similar term
        expandedAnnotation.semanticTags = List.of(similarTerm.iri);
        expandedAnnotation.resolvedTerm = similarTerm;
        
        // Indirect mapping: the seed's confidence, discounted, scaled by how similar
        // the class actually is (the caller has already applied the cutoff).
        expandedAnnotation.confidence = sourceAnnotation.confidence * EXPANSION_DISCOUNT * similarTerm.score;
        
        // Copy provenance
        expandedAnnotation.provenance = new Annotation.Provenance();
        expandedAnnotation.provenance.source = new Annotation.Source();
        expandedAnnotation.provenance.source.type = "ONTOLOGY";
        expandedAnnotation.provenance.source.name = similarTerm.ontology_name;
        expandedAnnotation.provenance.source.uri = similarTerm.ontology_name;
        expandedAnnotation.provenance.evidence = "OLS_LLM_SIMILAR";
        expandedAnnotation.provenance.accuracy = "NOT_SPECIFIED";
        expandedAnnotation.provenance.generator = "ZOOMA";
        expandedAnnotation.provenance.generatedDate = new Date().toString();
        
        // Propagate study/bioentity from source curated annotation
        expandedAnnotation.study = sourceAnnotation.study;
        expandedAnnotation.bioentity = sourceAnnotation.bioentity;
        
        // Chain the provenance: include all steps from source annotation + add LLM similar step
        List<V3MappingProvenanceStepDto> newProvenance = new ArrayList<>();
        if (sourceAnnotation.mappingProvenance != null) {
            newProvenance.addAll(sourceAnnotation.mappingProvenance);
        }
        
        // Add OLS LLM similar step
        V3MappingProvenanceStepDto step = new V3MappingProvenanceStepDto();
        step.method = "semantic";
        step.matchType = "OLS_LLM_SIMILAR";
        step.source = "ols:" + similarTerm.ontology_name;
        step.model = model;
        step.confidence = expandedAnnotation.confidence;
        step.input = sourceIri;
        step.matchedText = similarTerm.label;
        step.similarity = similarTerm.score;
        step.target = similarTerm.iri;
        
        newProvenance.add(step);
        
        expandedAnnotation.mappingProvenance = newProvenance;
        
        // Link back to source for reference
        expandedAnnotation.sourceAnnotation = sourceAnnotation;
        
        return expandedAnnotation;
    }

}
