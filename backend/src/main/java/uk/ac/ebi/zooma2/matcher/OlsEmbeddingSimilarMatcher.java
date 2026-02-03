package uk.ac.ebi.zooma2.matcher;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Matcher that uses OLS V2 embedding similarity API to find semantically similar terms.
 * Takes existing annotations and queries OLS for similar terms using embedding models.
 */
public class OlsEmbeddingSimilarMatcher implements AnnotationMatcher {

    private static final String OLS_BASE_URL = "https://wwwdev.ebi.ac.uk/ols4";
    private static final int DEFAULT_SIZE = 50;
    
    private final OlsClientRepo olsRepo;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final List<String> models;

    public OlsEmbeddingSimilarMatcher(OlsClientRepo olsRepo, List<String> models) {
        this.olsRepo = olsRepo;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.objectMapper = new ObjectMapper();
        this.models = models != null && !models.isEmpty() ? models : getDefaultModels();
    }

    private List<String> getDefaultModels() {
        // Use the embedding models available in OLS
        return List.of("llama-embed-nemotron-8b_pca512", "text-embedding-3-small");
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
        // Only run if we have preferred ontologies specified
        if (context.preferredOntologies == null || context.preferredOntologies.isEmpty()) {
            return Collections.emptyList();
        }

        // Only run if we have previous results to expand
        if (context.previousResults == null || context.previousResults.isEmpty()) {
            return Collections.emptyList();
        }

        // Check if we already have results from preferred ontologies
        Set<String> preferredOntologiesLower = context.preferredOntologies.stream()
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
            System.err.println("OLS Embedding Similar: Skipping expansion - already have results from preferred ontologies: " + 
                context.preferredOntologies);
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
            .collect(Collectors.groupingBy(a -> a.semanticTags.get(0)));

        // Get unique term IRIs to query
        Set<String> termIris = annotationsByTag.keySet();

        System.err.println("OLS LLM Similar: Querying " + termIris.size() + " term IRIs");

        // Query OLS LLM similar API for each term IRI and model
        Map<String, Set<String>> similarIrisBySource = new HashMap<>();
        
        for (String termIri : termIris) {
            for (String model : models) {
                List<String> similarIris = querySimilarTerms(termIri, model);
                if (!similarIris.isEmpty()) {
                    String key = termIri + "|" + model;
                    similarIrisBySource.put(key, new HashSet<>(similarIris));
                }
            }
        }

        if (similarIrisBySource.isEmpty()) {
            System.err.println("OLS LLM Similar: Found no similar terms");
            return Collections.emptyList();
        }

        System.err.println("OLS LLM Similar: Found " + similarIrisBySource.size() + " term-model combinations");

        // Collect all unique IRIs to resolve
        Set<String> allSimilarIris = similarIrisBySource.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toSet());

        // Resolve all similar terms from OLS V1 API
        var similarTermMap = olsRepo.resolveTerms(allSimilarIris);

        System.err.println("OLS LLM Similar: Resolved " + similarTermMap.size() + " similar terms");

        // Filter to only include terms from preferred ontologies
        similarTermMap = similarTermMap.entrySet().stream()
            .filter(e -> e.getValue() != null && 
                         e.getValue().ontology_name != null &&
                         preferredOntologiesLower.contains(e.getValue().ontology_name.toLowerCase()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        System.err.println("OLS LLM Similar: Filtered to " + similarTermMap.size() + 
            " terms from preferred ontologies: " + context.preferredOntologies);

        // Create new annotations for each similar term
        for (Map.Entry<String, Set<String>> entry : similarIrisBySource.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String sourceIri = parts[0];
            String model = parts[1];
            Set<String> similarIris = entry.getValue();

            // Get the source annotations that have this semantic tag
            List<Annotation> sourceAnnotations = annotationsByTag.get(sourceIri);
            if (sourceAnnotations == null || sourceAnnotations.isEmpty()) {
                continue;
            }

            // Create expanded annotations for each similar term
            for (String similarIri : similarIris) {
                var similarTerm = similarTermMap.get(similarIri);
                if (similarTerm == null) {
                    continue;
                }

                // Skip if the similar term is the same as the source
                if (similarIri.equals(sourceIri)) {
                    continue;
                }

                // Create new annotation for each source annotation
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
     * Query OLS V2 LLM similar API for a term IRI.
     * Returns list of similar term IRIs.
     */
    private List<String> querySimilarTerms(String termIri, String model) {
        try {
            // Double URL encode the IRI as required by OLS V2 API
            String encodedIri = URLEncoder.encode(URLEncoder.encode(termIri, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            String url = OLS_BASE_URL + "/api/v2/classes/" + encodedIri + 
                "/llm_similar?model=" + model + "&size=" + DEFAULT_SIZE;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseOlsV2Response(response.body());
            } else {
                System.err.println("OLS LLM Similar: HTTP " + response.statusCode() + " for " + termIri);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            System.err.println("OLS LLM Similar: Error querying " + termIri + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse OLS V2 API response to extract term IRIs.
     */
    private List<String> parseOlsV2Response(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode elements = root.path("elements");

            List<String> iris = new ArrayList<>();

            if (elements.isArray()) {
                for (JsonNode element : elements) {
                    String iri = element.path("iri").asText(null);
                    if (iri != null) {
                        iris.add(iri);
                    }
                }
            }

            return iris;
        } catch (Exception e) {
            System.err.println("OLS LLM Similar: Error parsing response: " + e.getMessage());
            return Collections.emptyList();
        }
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
        if (annotation.confidence == null) {
            return 0.0;
        }
        // Handle string confidence levels
        switch (annotation.confidence.toUpperCase()) {
            case "HIGH": return 1.0;
            case "GOOD": return 0.9;
            case "MEDIUM": return 0.7;
            case "LOW": return 0.5;
            default:
                // Try to parse as numeric
                try {
                    return Double.parseDouble(annotation.confidence);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
        }
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
        
        // Reduce confidence slightly since this is an indirect mapping
        expandedAnnotation.confidence = reduceConfidence(sourceAnnotation.confidence);
        
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
        step.confidence = 0.9; // High confidence for LLM similarity
        step.input = sourceIri;
        step.matchedText = similarTerm.label;
        step.similarity = null; // OLS V2 doesn't provide similarity scores
        step.target = similarTerm.iri;
        
        newProvenance.add(step);
        
        expandedAnnotation.mappingProvenance = newProvenance;
        
        // Link back to source for reference
        expandedAnnotation.sourceAnnotation = sourceAnnotation;
        
        return expandedAnnotation;
    }

    /**
     * Reduce confidence for indirect mappings.
     */
    private String reduceConfidence(String originalConfidence) {
        if (originalConfidence == null) {
            return "MEDIUM";
        }
        
        switch (originalConfidence) {
            case "HIGH":
                return "MEDIUM";
            case "GOOD":
                return "MEDIUM";
            case "MEDIUM":
                return "LOW";
            default:
                return "LOW";
        }
    }
}
