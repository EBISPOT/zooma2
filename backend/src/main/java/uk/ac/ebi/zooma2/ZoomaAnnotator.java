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

import uk.ac.ebi.zooma2.matcher.*;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OxoClient;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

public class ZoomaAnnotator {

    OlsClientRepo olsRepo;
    OxoClient oxoClient;
    PrefixMap prefixMap = new PrefixMap();
    Deduplicator deduplicator = new Deduplicator(prefixMap);

    // Individual matchers
    private final OlsLexicalMatcher olsLexicalMatcher;
    private final OlsEmbeddingMatcher olsEmbeddingMatcher;
    private final OxoMatcher oxoMatcher;
    private final OlsEmbeddingSimilarMatcher olsEmbeddingSimilarMatcher;

    public ZoomaAnnotator(OlsClientRepo olsRepo) {
        this.olsRepo = olsRepo;
        this.oxoClient = new OxoClient();

        // Initialize matchers
        this.olsLexicalMatcher = new OlsLexicalMatcher(olsRepo);
        var olsEmbeddingCfg = ZoomaConfig.config.ols_embedding;
        if (olsEmbeddingCfg != null) {
            double minSim = olsEmbeddingCfg.min_similarity != null ? olsEmbeddingCfg.min_similarity : 0.7;
            int maxRes = olsEmbeddingCfg.max_results != null ? olsEmbeddingCfg.max_results : 100;
            int timeoutMs = olsEmbeddingCfg.timeout_ms != null ? olsEmbeddingCfg.timeout_ms : 60000;
            this.olsEmbeddingMatcher = new OlsEmbeddingMatcher(olsRepo, minSim, maxRes, timeoutMs);
        } else {
            this.olsEmbeddingMatcher = new OlsEmbeddingMatcher(olsRepo);
        }
        this.oxoMatcher = new OxoMatcher(oxoClient, olsRepo);
        this.olsEmbeddingSimilarMatcher = new OlsEmbeddingSimilarMatcher(olsRepo, null, olsRepo.getSimilarSemaphore());
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
                    try {
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
                    } catch (java.io.UncheckedIOException e) {
                        throw e; // Client disconnected — propagate to stop all threads
                    } catch (Exception e) {
                        System.err.println("Error mapping property '" + prop.textToMap + "': " + e.getMessage());
                        onPropertyMapped.accept(prop, List.of(MapResult.error(prop.textToMap, prop.propertyType, e.getMessage())));
                    }
                })
            ).collect(Collectors.toList());

            // Wait for all to complete
            for (var future : futures) {
                try { future.get(); } catch (Exception e) {
                    if (e.getCause() instanceof java.io.UncheckedIOException) {
                        throw (java.io.UncheckedIOException) e.getCause();
                    }
                    System.err.println("Error mapping property: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Map a single property to ontology terms. Returns all MapResult candidates for this property.
     */
    public List<MapResult> mapOne(StringToMap s, Filter sources, String model) {
        try {
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
            if (term.isObsolete() && term.getReplacementIri() != null) {
                replacementIris.add(term.getReplacementIri());
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
            
            // Handle obsolete terms: replace if possible, drop if not
            if (term != null && term.isObsolete()) {
                if (term.getReplacementIri() != null) {
                    OlsTerm replacement = replacementTermMap.get(term.getReplacementIri());
                    if (replacement != null) {
                        finalTerm = replacement;
                        List<V3MappingProvenanceStepDto> newProvenance = new ArrayList<>(a.mappingProvenance);
                        newProvenance.add(V3MappingProvenanceStepDto.obsoleteReplacement(
                            term.iri, term.label,
                            replacement.iri, replacement.label,
                            term.ontology_name
                        ));
                        a.mappingProvenance = newProvenance;
                    } else {
                        return null; // obsolete, replacement not resolvable
                    }
                } else {
                    return null; // obsolete with no replacement
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
        }).filter(r -> r != null).collect(Collectors.toList());

        return results;
        } catch (Exception e) {
            System.err.println("Error mapping '" + s.textToMap + "': " + e.getMessage());
            return List.of(MapResult.error(s.textToMap, s.propertyType, e.getMessage()));
        }
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

        // Check for obsolete terms and resolve their replacements
        Set<String> replacementIris = new java.util.HashSet<>();
        for (var entry : termMap.entrySet()) {
            var term = entry.getValue();
            if (term.isObsolete()) {
                String replacementIri = term.getReplacementIri();
                if (replacementIri != null) {
                    replacementIris.add(replacementIri);
                }
            }
        }
        var replacementTermMap = olsRepo.resolveTerms(replacementIris);

        return preComputed.stream().map(a -> {
            var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
            var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
            MapResult r = new MapResult();
            r.propertyType = a.annotatedProperty.propertyType;
            r.textToMap = s.textToMap;

            OlsTerm term = expandedTag != null ? termMap.get(expandedTag) : null;
            OlsTerm finalTerm = term;

            // Handle obsolete terms: replace if possible, drop if not
            if (term != null && term.isObsolete()) {
                if (term.getReplacementIri() != null) {
                    OlsTerm replacement = replacementTermMap.get(term.getReplacementIri());
                    if (replacement != null) {
                        finalTerm = replacement;
                        List<V3MappingProvenanceStepDto> newProvenance = new ArrayList<>(a.mappingProvenance);
                        newProvenance.add(V3MappingProvenanceStepDto.obsoleteReplacement(
                            term.iri, term.label,
                            replacement.iri, replacement.label,
                            term.ontology_name
                        ));
                        a.mappingProvenance = newProvenance;
                    } else {
                        return null; // obsolete, replacement not resolvable
                    }
                } else {
                    return null; // obsolete with no replacement
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
        }).filter(r -> r != null).collect(Collectors.toList());
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
            // Run OLS lexical (fuzzy) + OLS embedding concurrently on virtual threads
            var olsLexicalFuture = executor.submit(() -> olsLexicalMatcher.findMatches(context));
            var olsEmbeddingFuture = executor.submit(() -> olsEmbeddingMatcher.findMatches(context));
            
            List<Annotation> allResults = new ArrayList<>();
            allResults.addAll(olsLexicalFuture.get());
            allResults.addAll(olsEmbeddingFuture.get());
            
            System.err.println("Total results for '" + stringToMap + "': " + allResults.size() + 
                " (" + olsLexicalMatcher.getName() + "=" + olsLexicalFuture.get().size() + 
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
            throw new RuntimeException("Search failed for '" + stringToMap + "': " + e.getMessage(), e);
        }
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
                boolean isCuration = "CURATION".equals(match.stringType);
                boolean isSynonym = "synonym".equalsIgnoreCase(match.stringType);

                double confidence;
                String matchType;
                String provenanceMethod;
                String sourceType;
                String sourceName;

                if (isCuration && isFullMatch) {
                    confidence = 0.95;
                    matchType = "CURATED_EXACT";
                    provenanceMethod = "curated";
                    sourceType = "DATABASE";
                    sourceName = match.source != null ? match.source : match.ontologyId;
                } else if (isFullMatch && isSynonym) {
                    confidence = 0.9;
                    matchType = "OLS_TEXT_TAGGER_SYNONYM";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                } else if (isFullMatch) {
                    confidence = 1.0;
                    matchType = "OLS_TEXT_TAGGER";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                } else if (isCuration) {
                    confidence = match.coverage * 0.89;
                    matchType = "CURATED_SUBSTRING";
                    provenanceMethod = "curated";
                    sourceType = "DATABASE";
                    sourceName = match.source != null ? match.source : match.ontologyId;
                } else {
                    confidence = match.coverage * 0.89;
                    matchType = "OLS_TEXT_TAGGER_SUBSTRING";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                }

                Annotation a = new Annotation();
                a.annotatedProperty = new Annotation.AnnotatedProperty();
                a.annotatedProperty.propertyType = "unspecified";
                a.annotatedProperty.propertyValue = inputTerm;
                a.semanticTags = List.of(match.termIri);
                a.confidence = confidence;
                a.provenance = new Annotation.Provenance();
                a.provenance.source = new Annotation.Source();
                a.provenance.source.type = sourceType;
                a.provenance.source.name = sourceName;
                a.provenance.source.uri = sourceName;
                a.provenance.evidence = matchType;
                a.provenance.generator = "ZOOMA";
                a.provenance.generatedDate = new Date().toString();

                if ("curated".equals(provenanceMethod)) {
                    a.mappingProvenance = List.of(V3MappingProvenanceStepDto.curated(
                        sourceName,
                        matchType,
                        inputTerm,
                        match.termLabel,
                        match.termIri,
                        match.coverage
                    ));
                } else {
                    a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical(
                        "ols:" + match.ontologyId,
                        matchType,
                        inputTerm,
                        match.termLabel,
                        match.termIri,
                        match.coverage
                    ));
                }
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

    boolean isNone(List<String> list) {
        return list != null &&
            list.size() == 1 &&
            (list.get(0).equals("none") || list.get(0).equals("Select None"));
    }

    
}
