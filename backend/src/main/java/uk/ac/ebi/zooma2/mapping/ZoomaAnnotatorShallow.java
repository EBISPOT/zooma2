package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.util.RequestCancellation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Handles Pass 1 (shallow-only search) of the two-pass batch mapping strategy.
 * Each property runs the tagger conversion and Phase 1 only. Properties for
 * which the escalation policy asks for Phase 2 are returned as
 * {@link StringMapper.MappingRun}s so the caller can complete them in a
 * subsequent deep pass without repeating Phase 1.
 */
public class ZoomaAnnotatorShallow {

    private final StringMapper stringMapper;
    private final OlsTextTaggerMatcher textTaggerService;
    private final Deduplicator deduplicator;

    public ZoomaAnnotatorShallow(StringMapper stringMapper, OlsTextTaggerMatcher textTaggerService, Deduplicator deduplicator) {
        this.stringMapper = stringMapper;
        this.textTaggerService = textTaggerService;
        this.deduplicator = deduplicator;
    }

    /**
     * Runs the shallow pass for all properties in parallel using virtual threads.
     * Calls {@code onPropertyMapped} for each property whose shallow results settle
     * the search; the rest are returned for the deep pass.
     *
     * @return the runs that need a subsequent deep pass
     */
    public List<StringMapper.MappingRun> runPass(
            List<StringToMap> properties,
            Map<String, List<Annotation>> tagTextResults,
            Filter filter,
            String model,
            List<String> excludeTermIds,
            BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {

        var cancelled = RequestCancellation.newFlag();
        var needsDeepSearch = new CopyOnWriteArrayList<StringMapper.MappingRun>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = properties.stream().map(prop ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                        var taggerAnnotations = tagTextResults.getOrDefault(prop.textToMap, List.of());
                        if (textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, filter, excludeTermIds)) {
                            var results = stringMapper.annotationsToMapResults(taggerAnnotations, prop, false);
                            onPropertyMapped.accept(prop, deduplicator.deduplicate(results, filter, excludeTermIds));
                            return;
                        }
                        var run = stringMapper.mapShallow(prop, taggerAnnotations, filter, model, null, excludeTermIds);
                        if (run.needsDeep) {
                            needsDeepSearch.add(run);
                            return;
                        }
                        onPropertyMapped.accept(prop, deduplicator.deduplicate(run.results, filter, excludeTermIds));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error mapping property '" + prop.textToMap + "': " + e.getMessage());
                        onPropertyMapped.accept(prop, List.of(MapResult.error(prop.textToMap, prop.propertyType, e.getMessage())));
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
}
