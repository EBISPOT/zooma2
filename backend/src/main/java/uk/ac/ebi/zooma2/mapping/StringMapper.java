package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.matcher.MatchContext;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.search.AnnotationEngine;
import uk.ac.ebi.zooma2.search.EscalationPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ObsoleteTermResolver obsoleteResolver;

    public StringMapper(AnnotationEngine annotationEngine, OlsClientRepo olsRepo, PrefixMap prefixMap) {
        this.annotationEngine = annotationEngine;
        this.olsRepo = olsRepo;
        this.prefixMap = prefixMap;
        this.obsoleteResolver = new ObsoleteTermResolver(olsRepo, prefixMap);
    }

    /**
     * One property's mapping in flight after the shallow phase: the results so
     * far (tagger + Phase 1), whether {@link EscalationPolicy} wants Phase 2, and
     * what Phase 2 needs so it need not repeat Phase 1.
     */
    public static final class MappingRun {
        public final StringToMap property;
        /** Engine (Phase 1) results followed by tagger results. */
        public final List<MapResult> results;
        public final boolean needsDeep;
        final MatchContext context;
        final List<Annotation> shallowAnnotations;
        final List<MapResult> engineResults;
        final List<MapResult> taggerResults;

        MappingRun(StringToMap property, MatchContext context, List<Annotation> shallowAnnotations,
                   List<MapResult> engineResults, List<MapResult> taggerResults, boolean needsDeep) {
            this.property = property;
            this.context = context;
            this.shallowAnnotations = shallowAnnotations;
            this.engineResults = engineResults;
            this.taggerResults = taggerResults;
            this.results = new ArrayList<>(engineResults);
            this.results.addAll(taggerResults);
            this.needsDeep = needsDeep;
        }
    }

    /**
     * Runs the full pipeline for a single string with no tagger results and
     * returns all {@link MapResult} candidates (not yet deduplicated).
     *
     * @param deep if {@code true}, always deep; {@code false}, never deep; {@code null}, auto
     */
    public List<MapResult> mapOne(StringToMap s, Filter sources, String model, Boolean deep) {
        return map(s, List.of(), sources, model, deep, null);
    }

    /**
     * Maps one string: tagger results, Phase 1, and Phase 2 when the
     * {@link EscalationPolicy} asks for it. Every entry point goes through here
     * (or through {@link #mapShallow}/{@link #mapDeep}, which split it in two),
     * so the escalation decision is made in one place over the same input.
     *
     * @param taggerAnnotations pre-computed text-tagger annotations for the text (shared, never modified)
     * @param excludeTermIds    terms the caller rejected; they do not count towards settling the search
     */
    public List<MapResult> map(StringToMap s, List<Annotation> taggerAnnotations, Filter filter, String model,
                               Boolean deep, List<String> excludeTermIds) {
        MappingRun run = mapShallow(s, taggerAnnotations, filter, model, deep, excludeTermIds);
        return run.needsDeep ? mapDeep(run) : run.results;
    }

    /** The tagger conversion plus Phase 1, and the escalation decision. */
    public MappingRun mapShallow(StringToMap s, List<Annotation> taggerAnnotations, Filter filter, String model,
                                 Boolean deep, List<String> excludeTermIds) {
        // Result order is engine (Phase 1, then Phase 2) followed by tagger, as it
        // always has been: candidate order is the tie-breaker for equal scores downstream.
        List<MapResult> taggerResults = new ArrayList<>();
        try {
            taggerResults.addAll(annotationsToMapResults(taggerAnnotations, s, false));
        } catch (java.io.UncheckedIOException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("Error converting tagger results for '" + s.textToMap + "': " + MapResult.describe(e));
            e.printStackTrace();
            taggerResults.add(MapResult.error(s.textToMap, s.propertyType, MapResult.describe(e)));
        }

        MatchContext context = new MatchContext(s.textToMap, s.propertyType, filter, model);
        List<MapResult> engineResults = new ArrayList<>();
        List<Annotation> shallow;
        try {
            shallow = annotationEngine.annotateShallow(context);
            engineResults.addAll(toMapResults(shallow, s));
        } catch (java.io.UncheckedIOException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("Error mapping '" + s.textToMap + "': " + MapResult.describe(e));
            e.printStackTrace();
            engineResults.add(MapResult.error(s.textToMap, s.propertyType, MapResult.describe(e)));
            return new MappingRun(s, context, List.of(), engineResults, taggerResults, false);
        }

        MappingRun run = new MappingRun(s, context, shallow, engineResults, taggerResults, false);
        boolean needsDeep = !Thread.currentThread().isInterrupted()
            && EscalationPolicy.needsDeep(run.results, filter, deep, Deduplicator.excludedForms(excludeTermIds, prefixMap));
        if (needsDeep && deep == null) {
            System.err.println("Shallow search found nothing that settles target ontologies "
                + (filter != null ? filter.targetOntologies : null) + " for '" + s.textToMap + "' — escalating to deep");
        }
        return needsDeep ? new MappingRun(s, context, shallow, engineResults, taggerResults, true) : run;
    }

    /**
     * Phase 2 for a run that needs it, seeded with its Phase-1 annotations; returns
     * the complete result list in the usual order: engine Phase 1, Phase 2, tagger.
     */
    public List<MapResult> mapDeep(MappingRun run) {
        List<MapResult> results = new ArrayList<>(run.engineResults);
        try {
            List<Annotation> deep = annotationEngine.annotateDeep(run.context, run.shallowAnnotations);
            results.addAll(toMapResults(deep, run.property));
        } catch (java.io.UncheckedIOException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("Error in deep search for '" + run.property.textToMap + "': " + MapResult.describe(e));
            e.printStackTrace();
            results.add(MapResult.error(run.property.textToMap, run.property.propertyType, MapResult.describe(e)));
        }
        results.addAll(run.taggerResults);
        return results;
    }

    /**
     * Converts pre-computed tagger {@link Annotation}s to {@link MapResult}s,
     * resolving full term details from OLS as needed.
     *
     * <p>The tagger annotations are fetched once per batch and shared by every
     * property with the same text, across threads and across the shallow and deep
     * passes, so this method must never modify them (see {@link #toMapResults}).
     *
     * @param preComputed tagger annotations (may carry a pre-resolved {@code resolvedTerm})
     * @param s           the original string-to-map (for propertyType context)
     * @param deep        whether to apply obsolete-term replacement
     */
    public List<MapResult> annotationsToMapResults(List<Annotation> preComputed, StringToMap s, boolean deep) {
        if (preComputed == null || preComputed.isEmpty()) {
            return List.of();
        }
        return toMapResults(preComputed, s);
    }

    /**
     * Converts {@link Annotation}s to {@link MapResult}s: resolves term details from
     * OLS, swaps obsolete terms for their replacements (recording the swap as an
     * extra provenance step) and drops obsolete terms that have no replacement.
     *
     * <p>This is the single conversion used by both {@link #mapOne} and
     * {@link #annotationsToMapResults}. It is side-effect free with respect to the
     * annotations: everything derived here (effective property type, re-resolved
     * obsolete terms, augmented provenance) lives in locals or on the new
     * {@link MapResult}s, because the input list may be shared between concurrently
     * running properties.
     */
    private List<MapResult> toMapResults(List<Annotation> annotations, StringToMap s) {
        // Matchers default a missing property type to "unspecified"; apply the same
        // rule here rather than reading it back from the (shared) annotations.
        final String effectivePropertyType = s.propertyType != null ? s.propertyType : "unspecified";

        // Only resolve terms that don't already carry a resolvedTerm from the matcher
        var termIrisToResolve = annotations.stream()
            .filter(a -> a.resolvedTerm == null)
            .flatMap(a -> a.semanticTags.stream())
            .map(tag -> prefixMap.shortFormToIri(tag))
            .collect(Collectors.toSet());

        var termMap = termIrisToResolve.isEmpty() ? Map.<String, OlsTerm>of() : olsRepo.resolveTerms(termIrisToResolve);

        // Build a combined map: pre-resolved terms + freshly resolved terms
        Map<String, OlsTerm> allTerms = new HashMap<>(termMap);
        for (var a : annotations) {
            if (a.resolvedTerm != null && a.resolvedTerm.iri != null) {
                allTerms.putIfAbsent(a.resolvedTerm.iri, a.resolvedTerm);
            }
        }

        // Obsolete term handling: follow each obsolete term's replacement chain to a
        // live term (see ObsoleteTermResolver). Resolutions are keyed by the obsolete
        // term's IRI and kept local; the shared annotations are never modified.
        final Map<String, ObsoleteTermResolver.Resolution> obsoleteResolutions =
            obsoleteResolver.resolve(allTerms);

        return annotations.stream().map(a -> {
            var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;
            var expandedTag = semanticTag != null ? prefixMap.shortFormToIri(semanticTag) : null;
            MapResult r = new MapResult();
            r.propertyType = effectivePropertyType;
            r.textToMap = s.textToMap;

            OlsTerm term = a.resolvedTerm != null ? a.resolvedTerm :
                           (expandedTag != null ? allTerms.get(expandedTag) : null);
            OlsTerm finalTerm = term;
            List<V3MappingProvenanceStepDto> provenance = a.mappingProvenance;

            // Swap an obsolete term for the live end of its replacement chain, recording
            // one provenance step per hop; drop it if the chain could not be completed.
            if (term != null && term.isObsolete()) {
                var resolution = obsoleteResolutions.get(term.iri);
                if (resolution == null) {
                    return null; // obsolete with no (resolvable) replacement
                }
                finalTerm = resolution.finalTerm;
                if (!resolution.steps.isEmpty()) {
                    provenance = new ArrayList<>(a.mappingProvenance);
                    provenance.addAll(resolution.steps);
                }
            }

            r.ontologyTermIri = finalTerm != null && finalTerm.iri != null ? finalTerm.iri
                : (expandedTag != null && expandedTag.startsWith("http") ? expandedTag : null);
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
            r.mappingProvenance = provenance;
            return r;
        }).filter(r -> r != null).collect(Collectors.toList());
    }
}
