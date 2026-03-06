package uk.ac.ebi.zooma2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
    Deduplicator deduplicator = new Deduplicator(prefixMap);

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
        return mapAll(stringsToMap, sources, "text-embedding-3-small", null, false);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model) {
        return mapAll(stringsToMap, sources, model, null, false);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model, List<String> excludeTermIds) {
        return mapAll(stringsToMap, sources, model, excludeTermIds, false);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model, List<String> excludeTermIds, boolean returnAll) {
        List<StringToMap> properties = stringsToMap.collect(Collectors.toList());

        List<String> allTerms = properties.stream()
            .map(p -> p.textToMap)
            .filter(v -> v != null && !v.isEmpty())
            .collect(Collectors.toList());
        var tagTextResults = bulkTagText(allTerms);

        return properties.stream()
            .parallel()
            .flatMap(s -> {
                var taggerAnnotations = tagTextResults.getOrDefault(s.textToMap, List.of());
                // Short-circuit: if text tagger found a 1.0 match from a target ontology, skip expensive matchers
                if (!returnAll && hasFullMatchFromTargetOntologies(taggerAnnotations, sources)) {
                    var results = annotationsToMapResults(taggerAnnotations, s);
                    var deduped = deduplicator.deduplicate(results, sources, excludeTermIds);
                    return deduped.stream();
                }
                var results = mapOne(s, sources, model);
                if (!taggerAnnotations.isEmpty()) {
                    var taggerResults = annotationsToMapResults(taggerAnnotations, s);
                    results.addAll(taggerResults);
                }
                var deduped = returnAll
                    ? deduplicator.deduplicateLight(results, sources, excludeTermIds)
                    : deduplicator.deduplicate(results, sources, excludeTermIds);
                return deduped.stream();
            })
            .collect(Collectors.toList());
    }

    /**
     * Process a batch of properties in parallel using virtual threads, calling
     * the consumer as each property completes. Each property gets its own virtual
     * thread so slow OXO calls never block other properties.
     */
    public void mapEach(List<StringToMap> properties, Filter filter, String model,
                        java.util.function.BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {
        List<String> allTerms = properties.stream()
            .map(p -> p.textToMap)
            .filter(v -> v != null && !v.isEmpty())
            .collect(Collectors.toList());
        var tagTextResults = bulkTagText(allTerms);
        System.err.println("Bulk tag_text returned matches for " + tagTextResults.size() + "/" + allTerms.size() + " terms");

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = properties.stream().map(prop ->
                executor.submit(() -> {
                    var taggerAnnotations = tagTextResults.getOrDefault(prop.textToMap, List.of());
                    // Short-circuit: if text tagger found a 1.0 match from a target ontology, skip expensive matchers
                    if (hasFullMatchFromTargetOntologies(taggerAnnotations, filter)) {
                        var results = annotationsToMapResults(taggerAnnotations, prop);
                        onPropertyMapped.accept(prop, deduplicator.deduplicate(results, filter));
                        return;
                    }
                    List<MapResult> results = mapOne(prop, filter, model);
                    if (!taggerAnnotations.isEmpty()) {
                        results.addAll(annotationsToMapResults(taggerAnnotations, prop));
                    }
                    onPropertyMapped.accept(prop, deduplicator.deduplicate(results, filter));
                })
            ).collect(Collectors.toList());

            // Wait for all to complete
            for (var future : futures) {
                try { future.get(); } catch (Exception e) {
                    System.err.println("Error mapping property: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Map a single property to ontology terms. Returns all MapResult candidates for this property.
     */
    public List<MapResult> mapOne(StringToMap s, Filter sources, String model) {
        var annotated = annotate(s.textToMap, s.propertyType, sources, model)
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
        
        var results = annotated.stream().map(a -> {
            var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
            // Expand short form to full IRI for lookup
            var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
            MapResult r = new MapResult();
            r.propertyType = s.propertyType != null ? s.propertyType : a.annotatedProperty.propertyType;
            r.textToMap = s.textToMap;
            
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
                r.ontologyTermLabel = r.textToMap;
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
            
            return r;
        }).collect(Collectors.toList());

        return results;
    }

    /**
     * Convert pre-computed annotations (e.g. from bulk text tagger) to MapResults,
     * resolving term details from OLS.
     */
    private List<MapResult> annotationsToMapResults(List<Annotation> preComputed, StringToMap s) {
        if (preComputed == null || preComputed.isEmpty()) {
            return List.of();
        }

        // Set the correct propertyType on each annotation
        for (var a : preComputed) {
            a.annotatedProperty.propertyType = s.propertyType != null ? s.propertyType : "unspecified";
        }

        var termIrisToResolve = preComputed.stream()
            .flatMap(a -> a.semanticTags.stream())
            .map(tag -> prefixMap.shortFormToIri(tag))
            .collect(Collectors.toSet());

        var termMap = olsRepo.resolveTerms(termIrisToResolve);

        return preComputed.stream().map(a -> {
            var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
            var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
            MapResult r = new MapResult();
            r.propertyType = a.annotatedProperty.propertyType;
            r.textToMap = s.textToMap;

            OlsTerm term = expandedTag != null ? termMap.get(expandedTag) : null;
            if (term != null) {
                r.ontologyTermID = term.short_form;
                r.ontologyTermLabel = term.label;
                r.ontologyTermSynonyms = term.synonyms != null ? String.join("|", term.synonyms) : null;
                r.ontologyURI = term.ontology_name;
            } else {
                r.ontologyTermLabel = r.textToMap;
            }
            if (r.ontologyTermID == null && expandedTag != null) {
                r.ontologyTermID = prefixMap.iriToShortForm(expandedTag);
            }
            if (r.ontologyTermID == null && semanticTag != null) {
                r.ontologyTermID = semanticTag;
            }
            r.mappingConfidence = a.confidence;
            r.datasource = a.provenance != null && a.provenance.source != null ? a.provenance.source.name : null;
            r.mappingProvenance = a.mappingProvenance;
            return r;
        }).collect(Collectors.toList());
    }

    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources) {
        return annotate(stringToMap, type, sources, "text-embedding-3-small");
    }

    /**
     * Run ALL search types in parallel and return all results (no deduplication or fallbacks).
     * This makes it clear what each search type is finding.
     * Uses individual matcher classes for each search strategy.
     */
    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources, String model) {
        
        MatchContext context = new MatchContext(stringToMap, type, sources, model);
        
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // Run all 4 matchers concurrently on virtual threads
            var curatedExactFuture = executor.submit(() -> curatedExactMatcher.findMatches(context));
            var curatedEmbeddingFuture = executor.submit(() -> curatedEmbeddingMatcher.findMatches(context));
            var olsLexicalFuture = executor.submit(() -> olsLexicalMatcher.findMatches(context));
            var olsEmbeddingFuture = executor.submit(() -> olsEmbeddingMatcher.findMatches(context));
            
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
            
            // If target ontologies specified, expand via OXO and OLS similarity in parallel
            if (context.targetOntologies != null && !context.targetOntologies.isEmpty() && !allResults.isEmpty()) {
                MatchContext expansionContext = context.withPreviousResults(allResults);
                
                // Run both expansion methods concurrently on virtual threads
                var oxoFuture = executor.submit(() -> oxoMatcher.findMatches(expansionContext));
                var similarFuture = executor.submit(() -> olsEmbeddingSimilarMatcher.findMatches(expansionContext));
                
                List<Annotation> oxoResults = oxoFuture.get();
                List<Annotation> embeddingSimilarResults = similarFuture.get();
                
                if (!oxoResults.isEmpty()) {
                    System.err.println("OXO expanded " + oxoResults.size() + " additional results to preferred ontologies");
                    allResults.addAll(oxoResults);
                }
                if (!embeddingSimilarResults.isEmpty()) {
                    System.err.println("OLS embedding similarity expanded " + embeddingSimilarResults.size() + " additional results");
                    allResults.addAll(embeddingSimilarResults);
                }
            }
            
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
        a.confidence = 0.6;
        
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
     * Bulk tag all input terms via OLS text tagger (single HTTP call).
     * Returns a map from input textToMap to a list of Annotations from the tagger.
     */
    private Map<String, List<Annotation>> bulkTagText(List<String> terms) {
        var tagResults = olsRepo.tagText(terms, null);
        Map<String, List<Annotation>> result = new java.util.HashMap<>();

        for (var entry : tagResults.entrySet()) {
            String inputTerm = entry.getKey();
            List<Annotation> annotations = new ArrayList<>();
            for (var match : entry.getValue()) {
                boolean isFullMatch = match.coverage >= 1.0;
                double confidence = isFullMatch ? 1.0 : match.coverage * 0.89;
                String matchType = isFullMatch ? "OLS_TEXT_TAGGER" : "OLS_TEXT_TAGGER_SUBSTRING";

                Annotation a = new Annotation();
                a.annotatedProperty = new Annotation.AnnotatedProperty();
                a.annotatedProperty.propertyType = "unspecified";
                a.annotatedProperty.propertyValue = inputTerm;
                a.semanticTags = List.of(match.termIri);
                a.confidence = confidence;
                a.provenance = new Annotation.Provenance();
                a.provenance.source = new Annotation.Source();
                a.provenance.source.type = "ONTOLOGY";
                a.provenance.source.name = match.ontologyId;
                a.provenance.source.uri = match.ontologyId;
                a.provenance.evidence = matchType;
                a.provenance.generator = "ZOOMA";
                a.provenance.generatedDate = new Date().toString();
                a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical(
                    "ols:" + match.ontologyId,
                    matchType,
                    inputTerm,
                    match.termLabel,
                    match.termIri,
                    match.coverage
                ));
                annotations.add(a);
            }
            result.put(inputTerm, annotations);
        }
        return result;
    }

    /**
     * Get available LLM models from OLS.
     */
    public List<Map<String, Object>> getEmbeddingModels() {
        return olsRepo.getEmbeddingModels();
    }

    /**
     * Check if a 1.0-confidence text tagger match exists.
     * If target ontologies are set, requires the match to be from one of them.
     * If no target ontologies are set, any 1.0 match qualifies.
     * Used to short-circuit expensive matchers when the tagger already found a perfect match.
     */
    private boolean hasFullMatchFromTargetOntologies(List<Annotation> taggerAnnotations, Filter filter) {
        if (taggerAnnotations == null || taggerAnnotations.isEmpty()) return false;
        boolean hasTargets = filter != null && filter.targetOntologies != null && !filter.targetOntologies.isEmpty();
        if (hasTargets) {
            Set<String> targets = filter.targetOntologies.stream().map(String::toLowerCase).collect(Collectors.toSet());
            return taggerAnnotations.stream().anyMatch(a ->
                a.confidence >= 1.0
                && a.provenance != null && a.provenance.source != null && a.provenance.source.name != null
                && targets.contains(a.provenance.source.name.toLowerCase())
            );
        }
        return taggerAnnotations.stream().anyMatch(a -> a.confidence >= 1.0);
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
