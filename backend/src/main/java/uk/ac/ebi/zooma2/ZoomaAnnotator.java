package uk.ac.ebi.zooma2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.ac.ebi.zooma2.embedding.EmbeddingService;
import uk.ac.ebi.zooma2.matcher.*;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.MappingTableEntry;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OxoClient;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

public class ZoomaAnnotator {

    MappingTablesRepo mappingTablesRepo;
    OlsClientRepo olsRepo;
    EmbeddingService embeddingService;
    OxoClient oxoClient;
    PrefixMap prefixMap = new PrefixMap();

    // Individual matchers
    private final CuratedExactMatcher curatedExactMatcher;
    private final CuratedEmbeddingMatcher curatedEmbeddingMatcher;
    private final OlsLexicalMatcher olsLexicalMatcher;
    private final OlsEmbeddingMatcher olsEmbeddingMatcher;
    private final OxoMatcher oxoMatcher;
    private final OlsEmbeddingSimilarMatcher olsEmbeddingSimilarMatcher;

    public ZoomaAnnotator(
        MappingTablesRepo mappingTablesRepo,
        OlsClientRepo olsRepo
    ) {
        this(mappingTablesRepo, olsRepo, null);
    }

    public ZoomaAnnotator(
        MappingTablesRepo mappingTablesRepo,
        OlsClientRepo olsRepo,
        EmbeddingService embeddingService
    ) {
        this.mappingTablesRepo = mappingTablesRepo;
        this.olsRepo = olsRepo;
        this.embeddingService = embeddingService;
        this.oxoClient = new OxoClient();

        // Initialize matchers
        this.curatedExactMatcher = new CuratedExactMatcher(mappingTablesRepo);
        this.curatedEmbeddingMatcher = new CuratedEmbeddingMatcher(mappingTablesRepo);
        this.olsLexicalMatcher = new OlsLexicalMatcher(olsRepo);
        this.olsEmbeddingMatcher = new OlsEmbeddingMatcher(olsRepo);
        this.oxoMatcher = new OxoMatcher(oxoClient, olsRepo);
        this.olsEmbeddingSimilarMatcher = new OlsEmbeddingSimilarMatcher(olsRepo, null); // Uses default models
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources) {
        return mapAll(stringsToMap, sources, "text-embedding-3-small", null);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model, List<String> preferredOntologies) {
        return stringsToMap
            .parallel()
            .flatMap(s -> {
                var annotated = annotate(s.propertyValue, s.propertyType, sources, model, preferredOntologies)
                    .collect(Collectors.toList());
                
                // Collect semantic tags and expand short forms to full IRIs
                var termIrisToResolve = annotated.stream()
                    .flatMap(a -> a.semanticTags.stream())
                    .map(tag -> prefixMap.shortFormToIri(tag))
                    .collect(Collectors.toSet());
                
                var termMap = olsRepo.resolveTerms(termIrisToResolve);
                
                // Check for obsolete terms and resolve their replacements
                Set<String> replacementIris = new java.util.HashSet<>();
                for (var term : termMap.values()) {
                    if (term.isObsolete() && term.term_replaced_by != null) {
                        replacementIris.add(term.term_replaced_by);
                    }
                }
                var replacementTermMap = olsRepo.resolveTerms(replacementIris);
                
                return annotated.stream().map(a -> {
                    var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
                    // Expand short form to full IRI for lookup
                    var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
                    MapResult r = new MapResult();
                    r.propertyType = a.annotatedProperty.propertyType;
                    r.propertyValue = a.annotatedProperty.propertyValue;
                    
                    OlsTerm term = expandedTag != null ? termMap.get(expandedTag) : null;
                    OlsTerm finalTerm = term;
                    
                    // Check if term is obsolete and has a replacement
                    if (term != null && term.isObsolete() && term.term_replaced_by != null) {
                        OlsTerm replacement = replacementTermMap.get(term.term_replaced_by);
                        if (replacement != null) {
                            // Use the replacement term for the final result
                            finalTerm = replacement;
                            
                            // Add obsolete replacement step to provenance
                            List<V3MappingProvenanceStepDto> newProvenance = new ArrayList<>(a.mappingProvenance);
                            newProvenance.add(V3MappingProvenanceStepDto.obsoleteReplacement(
                                term.iri, term.label,
                                replacement.iri, replacement.label,
                                term.ontology_name
                            ));
                            a.mappingProvenance = newProvenance;
                        }
                    }
                    
                    if (finalTerm != null) {
                        r.ontologyTermID = finalTerm.short_form;
                        r.ontologyTermLabel = finalTerm.label;
                        r.ontologyTermSynonyms = finalTerm.synonyms != null ? String.join("|", finalTerm.synonyms) : null;
                        r.ontologyURI = finalTerm.ontology_name;
                    } else {
                        r.ontologyTermLabel = r.propertyValue;
                    }
                    if(r.ontologyTermID == null && expandedTag != null) {
                        r.ontologyTermID = prefixMap.iriToShortForm(expandedTag);
                    }
                    if(r.ontologyTermID == null && semanticTag != null) {
                        r.ontologyTermID = semanticTag;
                    }
                    r.mappingConfidence = a.confidence;
                    r.datasource = a.provenance != null && a.provenance.source != null ? a.provenance.source.name : null;
                    r.mappingProvenance = a.mappingProvenance;
                    
                    // Debug UMLS results
                    if (r.ontologyTermID != null && (r.ontologyTermID.toUpperCase().contains("UMLS") || r.datasource != null && r.datasource.toLowerCase().contains("umls"))) {
                        System.err.println("UMLS result: termId=" + r.ontologyTermID + ", label=" + r.ontologyTermLabel + ", datasource=" + r.datasource + ", provenance steps=" + (r.mappingProvenance != null ? r.mappingProvenance.size() : 0));
                    }
                    
                    return r;
                });
            })
            .collect(Collectors.toList());
    }

    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources) {
        return annotate(stringToMap, type, sources, "text-embedding-3-small", null);
    }

    /**
     * Run ALL search types in parallel and return all results (no deduplication or fallbacks).
     * This makes it clear what each search type is finding.
     * Uses individual matcher classes for each search strategy.
     */
    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources, String model, List<String> preferredOntologies) {
        
        MatchContext context = new MatchContext(stringToMap, type, sources, model, preferredOntologies);
        
        // 1. Curated exact matches
        var curatedExactFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> 
            curatedExactMatcher.findMatches(context)
        );
        
        // 2. Curated embedding matches
        var curatedEmbeddingFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> 
            curatedEmbeddingMatcher.findMatches(context)
        );
        
        // 3. OLS lexical matches
        var olsLexicalFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> 
            olsLexicalMatcher.findMatches(context)
        );
        
        // 4. OLS embedding search
        var olsEmbeddingFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> 
            olsEmbeddingMatcher.findMatches(context)
        );
        
        try {
            List<Annotation> allResults = new ArrayList<>();
            allResults.addAll(curatedExactFuture.get());
            allResults.addAll(curatedEmbeddingFuture.get());
            allResults.addAll(olsLexicalFuture.get());
            allResults.addAll(olsEmbeddingFuture.get());
            
            System.err.println("Total results for '" + stringToMap + "': " + allResults.size() + 
                " (" + curatedExactMatcher.getName() + "=" + curatedExactFuture.get().size() + 
                ", " + curatedEmbeddingMatcher.getName() + "=" + curatedEmbeddingFuture.get().size() +
                ", " + olsLexicalMatcher.getName() + "=" + olsLexicalFuture.get().size() + 
                ", " + olsEmbeddingMatcher.getName() + "=" + olsEmbeddingFuture.get().size() + ")");
            
            // If preferred ontologies are specified, try to expand results using OXO and OLS LLM similarity
            if (preferredOntologies != null && !preferredOntologies.isEmpty() && !allResults.isEmpty()) {
                // Use the same context for both expansion methods (don't feed one into the other)
                MatchContext expansionContext = context.withPreviousResults(allResults);
                
                // Try OXO cross-reference mappings
                List<Annotation> oxoResults = oxoMatcher.findMatches(expansionContext);
                
                // Try OLS embedding similarity search for additional related terms (independently)
                List<Annotation> embeddingSimilarResults = olsEmbeddingSimilarMatcher.findMatches(expansionContext);
                
                // Add both sets of results
                if (!oxoResults.isEmpty()) {
                    System.err.println("OXO expanded " + oxoResults.size() + " additional results to preferred ontologies");
                    allResults.addAll(oxoResults);
                }
                
                if (!embeddingSimilarResults.isEmpty()) {
                    System.err.println("OLS embedding similarity expanded " + embeddingSimilarResults.size() + " additional results");
                    allResults.addAll(embeddingSimilarResults);
                }
            }
            
            System.err.println("Total annotations before return: " + allResults.size());
            return allResults.stream();
            
        } catch (Exception e) {
            System.err.println("Error in parallel search: " + e.getMessage());
            e.printStackTrace();
            return Stream.empty();
        }
    }

    private Stream<MappingTableEntry> getMappingsFromTables(String stringToMap, String type, Filter sources) {
        if(sources == null || !isNone(sources.required)) {
            var mappings = mappingTablesRepo.allMappingsForString(stringToMap);
            mappings = filterMappings(mappings, type, sources);
            return mappings;
        } else {
            return Stream.<MappingTableEntry>empty();
        }
    }

    private Annotation createAnnotationFromOlsTerm(OlsTerm term, String stringToMap, String type, String evidence) {
        return createAnnotationFromOlsTerm(term, stringToMap, type, evidence, null);
    }

    private Annotation createAnnotationFromOlsTerm(OlsTerm term, String stringToMap, String type, String evidence, String model) {
        Annotation a = new Annotation();
        
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyType = type != null ? type : "unspecified";
        a.annotatedProperty.propertyValue = stringToMap;
        
        a.semanticTags = List.of(term.iri);
        a.confidence = "MEDIUM";
        
        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "ONTOLOGY";
        a.provenance.source.name = term.ontology_name;
        a.provenance.source.uri = term.ontology_name;
        a.provenance.evidence = evidence;
        a.provenance.accuracy = "NOT_SPECIFIED";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();
        a.provenance.annotator = null;
        a.provenance.annotationDate = null;

        // Set mapping provenance based on evidence type
        if (evidence.contains("SEMANTIC") || evidence.contains("LLM")) {
            a.mappingProvenance = List.of(V3MappingProvenanceStepDto.semantic(
                "ols:" + term.ontology_name,
                model,
                stringToMap,
                term.label,
                term.iri,
                term.score != null ? term.score : null,
                null,
                "OLS_LLM_EMBEDDING"
            ));
        } else {
            a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical(
                term.ontology_name,
                term.label.equalsIgnoreCase(stringToMap) ? "exact_label" : "synonym",
                stringToMap,
                term.label,
                term.iri,
                0.9
            ));
        }
        
        return a;
    }

    /**
     * Get available LLM models from OLS.
     */
    public List<Map<String, Object>> getEmbeddingModels() {
        return olsRepo.getEmbeddingModels();
    }

    /**
     * Check if embedding service is enabled.
     */
    public boolean isEmbeddingServiceEnabled() {
        return embeddingService != null;
    }


    Stream<MappingTableEntry> filterMappings(Stream<MappingTableEntry> mappings, String type, Filter filter) {

        return mappings
            .filter(a -> type == null || a.propertyType == null || a.propertyType.equals(type))
            .filter(a -> {
                
                if(filter == null) return true;

                if(filter.required.size() > 0) {
                    if(!filter.required.contains(a.databaseId)) {
                        return false;
                    }
                }

                return true;
            });

    }

    boolean isNone(List<String> list) {
        return list != null &&
            list.size() == 1 &&
            (list.get(0).equals("none") || list.get(0).equals("Select None"));
    }

    
}
