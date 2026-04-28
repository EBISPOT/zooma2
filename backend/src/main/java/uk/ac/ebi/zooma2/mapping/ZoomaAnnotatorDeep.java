package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.util.RequestCancellation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Handles Pass 2 (full deep search) of the two-pass batch mapping strategy.
 * Runs deep embedding, OXO and similarity lookups for properties that
 * {@link ZoomaAnnotatorShallow} could not resolve to a target-ontology term.
 */
public class ZoomaAnnotatorDeep {

    private final StringMapper stringMapper;
    private final Deduplicator deduplicator;

    public ZoomaAnnotatorDeep(StringMapper stringMapper, Deduplicator deduplicator) {
        this.stringMapper = stringMapper;
        this.deduplicator = deduplicator;
    }

    /**
     * Runs a deep-search pass for the given properties in parallel using virtual threads,
     * calling {@code onPropertyMapped} as each property completes.
     */
    public void runPass(
            List<StringToMap> properties,
            Map<String, List<Annotation>> tagTextResults,
            Filter filter,
            String model,
            List<String> excludeTermIds,
            BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {

        var cancelled = RequestCancellation.newFlag();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = properties.stream().map(prop ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                        List<MapResult> results = stringMapper.mapOne(prop, filter, model, true);
                        var taggerAnnotations = tagTextResults.getOrDefault(prop.textToMap, List.of());
                        if (!taggerAnnotations.isEmpty()) {
                            results.addAll(stringMapper.annotationsToMapResults(taggerAnnotations, prop, false));
                        }
                        onPropertyMapped.accept(prop, deduplicator.deduplicate(results, filter, excludeTermIds));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error in deep search for '" + prop.textToMap + "': " + e.getMessage());
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
                    System.err.println("Error in deep mapping: " + e.getMessage());
                }
            }
        } finally {
            RequestCancellation.clearFlag();
        }
    }
}
