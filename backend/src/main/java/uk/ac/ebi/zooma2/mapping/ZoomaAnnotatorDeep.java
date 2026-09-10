package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.util.RequestCancellation;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Handles Pass 2 (deep search) of the two-pass batch mapping strategy: completes
 * the runs {@link ZoomaAnnotatorShallow} deferred by running Phase 2 on each,
 * seeded with the Phase-1 annotations it already holds.
 */
public class ZoomaAnnotatorDeep {

    private final StringMapper stringMapper;
    private final Deduplicator deduplicator;

    public ZoomaAnnotatorDeep(StringMapper stringMapper, Deduplicator deduplicator) {
        this.stringMapper = stringMapper;
        this.deduplicator = deduplicator;
    }

    /**
     * Runs the deep pass for the given runs in parallel using virtual threads,
     * calling {@code onPropertyMapped} as each completes.
     */
    public void runPass(
            List<StringMapper.MappingRun> runs,
            Filter filter,
            List<String> excludeTermIds,
            BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {

        // Join the request's cancellation flag (created by the streaming endpoint) so a
        // client disconnect reaches this pass and every later one; own it only if absent.

        try (var scope = RequestCancellation.acquire();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancelled = scope.flag();
            var futures = runs.stream().map(run ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    StringToMap prop = run.property;
                    try {
                        List<MapResult> results = stringMapper.mapDeep(run);
                        onPropertyMapped.accept(prop, deduplicator.deduplicate(results, filter, excludeTermIds));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error in deep search for '" + prop.textToMap + "': " + MapResult.describe(e));
                        e.printStackTrace();
                        onPropertyMapped.accept(prop, List.of(MapResult.error(prop.textToMap, prop.propertyType, MapResult.describe(e))));
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
        }
    }
}
