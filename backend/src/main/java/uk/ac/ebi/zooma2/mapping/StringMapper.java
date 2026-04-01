package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.search.AnnotationEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps a single string to a ranked list of {@link MapResult} candidates.
 *
 * <p>Combines results from {@link AnnotationEngine} (live OLS search) with
 * pre-computed tagger annotations, resolves term details from OLS, handles
 * obsolete-term replacement, and returns deduplicated results.
 */
public class StringMapper {

    private final AnnotationEngine annotationEngine;
    private final OlsClientRepo olsRepo;
    private final PrefixMap prefixMap;

    public StringMapper(AnnotationEngine annotationEngine, OlsClientRepo olsRepo, PrefixMap prefixMap) {
        this.annotationEngine = annotationEngine;
        this.olsRepo = olsRepo;
        this.prefixMap = prefixMap;
    }

    /**
     * Runs the full annotation pipeline for a single string and returns all
     * {@link MapResult} candidates (not yet deduplicated).
     *
     * @param s       the string (and optional property type) to map
     * @param sources filter specifying target ontologies / datasources
     * @param model   embedding model identifier
     * @param deep    if {@code true}, runs the full deep-search phase
     */
    public List<MapResult> mapOne(StringToMap s, Filter sources, String model, boolean deep) {
        try {
            var annotated = annotationEngine.annotate(s.textToMap, s.propertyType, sources, model, deep)
                .collect(Collectors.toList());

            // Only resolve terms that don't already carry a resolvedTerm from the matcher
            var termIrisToResolve = annotated.stream()
                .filter(a -> a.resolvedTerm == null)
                .flatMap(a -> a.semanticTags.stream())
                .map(tag -> prefixMap.shortFormToIri(tag))
                .collect(Collectors.toSet());

            var termMap = termIrisToResolve.isEmpty() ? Map.<String, OlsTerm>of() : olsRepo.resolveTerms(termIrisToResolve);

            // Build a combined map: pre-resolved terms + freshly resolved terms
            Map<String, OlsTerm> allTerms = new HashMap<>(termMap);
            for (var a : annotated) {
                if (a.resolvedTerm != null && a.resolvedTerm.iri != null) {
                    allTerms.putIfAbsent(a.resolvedTerm.iri, a.resolvedTerm);
                }
            }

            // Obsolete term handling: resolve any that lack replacementIri, then fetch replacements
            Set<String> obsoleteToResolve = new HashSet<>();
            for (var term : allTerms.values()) {
                if (term.isObsolete() && term.getReplacementIri() == null) {
                    obsoleteToResolve.add(term.iri);
                }
            }
            if (!obsoleteToResolve.isEmpty()) {
                var resolvedObsolete = olsRepo.resolveTerms(obsoleteToResolve);
                for (var entry : resolvedObsolete.entrySet()) {
                    allTerms.put(entry.getKey(), entry.getValue());
                    for (var a : annotated) {
                        if (a.resolvedTerm != null && entry.getKey().equals(a.resolvedTerm.iri)) {
                            a.resolvedTerm = entry.getValue();
                        }
                    }
                }
            }

            Set<String> replacementIris = new HashSet<>();
            for (var term : allTerms.values()) {
                if (term.isObsolete() && term.getReplacementIri() != null) {
                    replacementIris.add(term.getReplacementIri());
                }
            }
            Map<String, OlsTerm> replacementTermMap = olsRepo.resolveTerms(replacementIris);
            final Map<String, OlsTerm> replacements = replacementTermMap;

            return annotated.stream().map(a -> {
                var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
                var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
                MapResult r = new MapResult();
                r.propertyType = s.propertyType != null ? s.propertyType : a.annotatedProperty.propertyType;
                r.textToMap = s.textToMap;

                OlsTerm term = a.resolvedTerm != null ? a.resolvedTerm :
                               (expandedTag != null ? allTerms.get(expandedTag) : null);
                OlsTerm finalTerm = term;

                // Replace obsolete terms if possible, drop if not
                if (term != null && term.isObsolete()) {
                    if (term.getReplacementIri() != null) {
                        OlsTerm replacement = replacements.get(term.getReplacementIri());
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

        } catch (Exception e) {
            System.err.println("Error mapping '" + s.textToMap + "': " + e.getMessage());
            return List.of(MapResult.error(s.textToMap, s.propertyType, e.getMessage()));
        }
    }

    /**
     * Converts pre-computed tagger {@link Annotation}s to {@link MapResult}s,
     * resolving full term details from OLS as needed.
     *
     * @param preComputed tagger annotations (may carry a pre-resolved {@code resolvedTerm})
     * @param s           the original string-to-map (for propertyType context)
     * @param deep        whether to apply obsolete-term replacement
     */
    public List<MapResult> annotationsToMapResults(List<Annotation> preComputed, StringToMap s, boolean deep) {
        if (preComputed == null || preComputed.isEmpty()) {
            return List.of();
        }

        for (var a : preComputed) {
            a.annotatedProperty.propertyType = s.propertyType != null ? s.propertyType : "unspecified";
        }

        var termIrisToResolve = preComputed.stream()
            .filter(a -> a.resolvedTerm == null)
            .flatMap(a -> a.semanticTags.stream())
            .map(tag -> prefixMap.shortFormToIri(tag))
            .collect(Collectors.toSet());

        var termMap = termIrisToResolve.isEmpty() ? Map.<String, OlsTerm>of() : olsRepo.resolveTerms(termIrisToResolve);

        Map<String, OlsTerm> allTerms = new HashMap<>(termMap);
        for (var a : preComputed) {
            if (a.resolvedTerm != null && a.resolvedTerm.iri != null) {
                allTerms.putIfAbsent(a.resolvedTerm.iri, a.resolvedTerm);
            }
        }

        Map<String, OlsTerm> replacementTermMap = Map.of();
        Set<String> obsoleteToResolve = new HashSet<>();
        for (var term : allTerms.values()) {
            if (term.isObsolete() && term.getReplacementIri() == null) {
                obsoleteToResolve.add(term.iri);
            }
        }
        if (!obsoleteToResolve.isEmpty()) {
                var resolvedObsolete = olsRepo.resolveTerms(obsoleteToResolve);
            for (var entry : resolvedObsolete.entrySet()) {
                allTerms.put(entry.getKey(), entry.getValue());
                for (var a : preComputed) {
                    if (a.resolvedTerm != null && entry.getKey().equals(a.resolvedTerm.iri)) {
                        a.resolvedTerm = entry.getValue();
                    }
                }
            }
        }

        Set<String> replacementIris = new HashSet<>();
        for (var term : allTerms.values()) {
            if (term.isObsolete() && term.getReplacementIri() != null) {
                replacementIris.add(term.getReplacementIri());
            }
        }
        replacementTermMap = olsRepo.resolveTerms(replacementIris);
        final Map<String, OlsTerm> replacements = replacementTermMap;

        return preComputed.stream().map(a -> {
            var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
            var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
            MapResult r = new MapResult();
            r.propertyType = a.annotatedProperty.propertyType;
            r.textToMap = s.textToMap;

            OlsTerm term = a.resolvedTerm != null ? a.resolvedTerm :
                           (expandedTag != null ? allTerms.get(expandedTag) : null);
            OlsTerm finalTerm = term;

            if (term != null && term.isObsolete()) {
                if (term.getReplacementIri() != null) {
                    OlsTerm replacement = replacements.get(term.getReplacementIri());
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
                        return null;
                    }
                } else {
                    return null;
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
}
