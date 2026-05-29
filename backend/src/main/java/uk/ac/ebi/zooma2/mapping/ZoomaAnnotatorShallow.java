package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.AncestorSurfacer;
import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.search.AnnotationEngine;
import uk.ac.ebi.zooma2.rules.RuleContext;
import uk.ac.ebi.zooma2.util.RequestCancellation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Handles Pass 1 (shallow-only search) of the two-pass batch mapping strategy.
 * Each property is searched without deep embedding or OXO lookups.
 * Properties where no target-ontology result was found are returned so the
 * caller can run a subsequent deep pass on them.
 */
public class ZoomaAnnotatorShallow {

    private final StringMapper stringMapper;
    private final OlsTextTaggerMatcher textTaggerService;
    private final Deduplicator deduplicator;
    private final AncestorSurfacer ancestorSurfacer;

    public ZoomaAnnotatorShallow(StringMapper stringMapper, OlsTextTaggerMatcher textTaggerService, Deduplicator deduplicator) {
        this(stringMapper, textTaggerService, deduplicator, null);
    }

    public ZoomaAnnotatorShallow(StringMapper stringMapper, OlsTextTaggerMatcher textTaggerService,
                                 Deduplicator deduplicator, AncestorSurfacer ancestorSurfacer) {
        this.stringMapper = stringMapper;
        this.textTaggerService = textTaggerService;
        this.deduplicator = deduplicator;
        this.ancestorSurfacer = ancestorSurfacer;
    }

    private List<MapResult> withAncestor(List<MapResult> deduped, Filter filter) {
        return ancestorSurfacer != null ? ancestorSurfacer.augment(deduped, filter) : deduped;
    }

    /**
     * Runs a shallow-only pass for all properties in parallel using virtual threads.
     * Calls {@code onPropertyMapped} for each property whose shallow results are
     * sufficient; properties that need a deep pass are returned for deferred processing.
     *
     * @return the subset of properties that need a subsequent deep pass
     */
    public List<StringToMap> runPass(
            List<StringToMap> properties,
            Map<String, List<Annotation>> tagTextResults,
            Filter filter,
            String model,
            List<String> excludeTermIds,
            BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {

        var cancelled = RequestCancellation.newFlag();
        var needsDeepSearch = new CopyOnWriteArrayList<StringToMap>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = properties.stream().map(prop ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    AnnotationEngine.setShallowPassOnly(true);
                    try {
                        RuleContext ruleCtx = RuleContext.forQuery(prop, filter, excludeTermIds);
                        var taggerAnnotations = tagTextResults.getOrDefault(prop.textToMap, List.of());
                        if (textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, filter)) {
                            var results = stringMapper.annotationsToMapResults(taggerAnnotations, prop, false);
                            onPropertyMapped.accept(prop, withAncestor(deduplicator.deduplicate(results, filter, excludeTermIds, ruleCtx), filter));
                            return;
                        }
                        List<MapResult> results = stringMapper.mapOne(prop, filter, model, false, ruleCtx);
                        if (!taggerAnnotations.isEmpty()) {
                            results.addAll(stringMapper.annotationsToMapResults(taggerAnnotations, prop, false));
                        }
                        if (needsDeep(results, filter)) {
                            needsDeepSearch.add(prop);
                            return;
                        }
                        onPropertyMapped.accept(prop, withAncestor(deduplicator.deduplicate(results, filter, excludeTermIds, ruleCtx), filter));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error mapping property '" + prop.textToMap + "': " + e.getMessage());
                        onPropertyMapped.accept(prop, List.of(MapResult.error(prop.textToMap, prop.propertyType, e.getMessage())));
                    } finally {
                        AnnotationEngine.setShallowPassOnly(false);
                    }
                })
            ).collect(Collectors.toList());

            for (var future : futures) {
                try { future.get(); } catch (Exception e) {
                    if (e.getCause() instanceof java.io.UncheckedIOException) {
                        cancelled.set(true);
                        futures.forEach(f -> f.cancel(true));
                        throw (java.io.UncheckedIOException) e.getCause();
                    }
                    System.err.println("Error mapping property: " + e.getMessage());
                }
            }
        } finally {
            RequestCancellation.clearFlag();
        }

        return needsDeepSearch;
    }

    /**
     * Returns {@code true} if the shallow-pass results indicate this property needs a
     * deep search: target ontologies were specified, results were found, but none came
     * from a target ontology.
     */
    private boolean needsDeep(List<MapResult> results, Filter filter) {
        if (filter.targetOntologies == null || filter.targetOntologies.isEmpty()) return false;
        if (results.isEmpty()) return false;
        Set<String> targetsLower = filter.targetOntologies.stream()
            .map(String::toLowerCase).collect(Collectors.toSet());
        return results.stream().noneMatch(r ->
            r.ontologyURI != null && targetsLower.contains(r.ontologyURI.toLowerCase()));
    }
}
