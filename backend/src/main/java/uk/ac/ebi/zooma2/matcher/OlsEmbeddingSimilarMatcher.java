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
import java.util.concurrent.Semaphore;
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
    private final Semaphore semaphore;

    public OlsEmbeddingSimilarMatcher(OlsClientRepo olsRepo, List<String> models) {
        this(olsRepo, models, new Semaphore(10));
    }

    public OlsEmbeddingSimilarMatcher(OlsClientRepo olsRepo, List<String> models, Semaphore semaphore) {
        this.olsRepo = olsRepo;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.objectMapper = new ObjectMapper();
        this.models = models != null && !models.isEmpty() ? models : getDefaultModels();
        this.semaphore = semaphore;
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
                List<OlsTerm> similarTerms = querySimilarTerms(termIri, model);
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
     * Returns list of similar OlsTerms with full metadata from the response.
     */
    private List<OlsTerm> querySimilarTerms(String termIri, String model) {
        semaphore.acquireUninterruptibly();
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
        } finally {
            semaphore.release();
        }
    }

    /**
     * Parse OLS V2 API response to extract full OlsTerm objects.
     */
    private List<OlsTerm> parseOlsV2Response(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode elements = root.path("elements");

            List<OlsTerm> terms = new ArrayList<>();

            if (elements.isArray()) {
                for (JsonNode element : elements) {
                    OlsTerm term = new OlsTerm();
                    term.iri = element.path("iri").asText(null);
                    if (term.iri == null) continue;

                    JsonNode labelNode = element.path("label");
                    if (labelNode.isArray() && labelNode.size() > 0) {
                        term.label = labelNode.get(0).asText(null);
                    } else if (labelNode.isTextual()) {
                        term.label = labelNode.asText(null);
                    }

                    term.short_form = element.has("shortForm") ? element.get("shortForm").asText(null) :
                                      (element.has("short_form") ? element.get("short_form").asText(null) : null);
                    term.ontology_name = element.has("ontologyId") ? element.get("ontologyId").asText(null) :
                                         (element.has("ontology_name") ? element.get("ontology_name").asText(null) : null);

                    JsonNode synsNode = element.path("synonyms");
                    if (synsNode.isArray()) {
                        List<String> syns = new ArrayList<>();
                        for (JsonNode s : synsNode) syns.add(s.asText());
                        term.synonyms = syns;
                    }

                    if (element.has("isObsolete")) {
                        term.is_obsolete = element.get("isObsolete").asBoolean(false);
                    } else if (element.has("is_obsolete")) {
                        term.is_obsolete = element.get("is_obsolete").asBoolean(false);
                    }

                    if (element.has("score")) {
                        term.score = element.get("score").asDouble();
                    }

                    terms.add(term);
                }
            }

            return terms;
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
    private double reduceConfidence(double originalConfidence) {
        return originalConfidence * 0.7;
    }
}
