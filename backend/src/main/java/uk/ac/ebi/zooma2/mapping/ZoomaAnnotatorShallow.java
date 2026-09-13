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
 * Each string runs the tagger conversion and Phase 1 only. Strings for
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
     * Runs the shallow pass for all strings in parallel using virtual threads.
     * Calls {@code onStringMapped} for each string whose shallow results settle
     * the search; the rest are returned for the deep pass.
     *
     * @return the runs that need a subsequent deep pass
     */
    public List<StringMapper.MappingRun> runPass(
            List<StringToMap> stringsToMap,
            Map<String, List<Annotation>> tagTextResults,
            Filter filter,
            String model,
            List<String> excludeTermIds,
            BiConsumer<StringToMap, List<MapResult>> onStringMapped) {

        // Join the request's cancellation flag (created by the streaming endpoint) so a
        // client disconnect reaches this pass and every later one; own it only if absent.
        var needsDeepSearch = new CopyOnWriteArrayList<StringMapper.MappingRun>();

        try (var scope = RequestCancellation.acquire();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancelled = scope.flag();
            var futures = stringsToMap.stream().map(s ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                        var taggerAnnotations = tagTextResults.getOrDefault(s.textToMap, List.of());
                        if (textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, filter, excludeTermIds)) {
                            var results = stringMapper.annotationsToMapResults(taggerAnnotations, s, false);
                            onStringMapped.accept(s, deduplicator.deduplicate(results, filter, excludeTermIds));
                            return;
                        }
                        var run = stringMapper.mapShallow(s, taggerAnnotations, filter, model, null, excludeTermIds);
                        if (run.needsDeep) {
                            needsDeepSearch.add(run);
                            return;
                        }
                        onStringMapped.accept(s, deduplicator.deduplicate(run.results, filter, excludeTermIds));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error mapping '" + s.textToMap + "': " + MapResult.describe(e));
                        e.printStackTrace();
                        onStringMapped.accept(s, List.of(MapResult.error(s.textToMap, s.propertyType, MapResult.describe(e))));
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
                    System.err.println("Error mapping string: " + e.getMessage());
                }
            }
        }

        return needsDeepSearch;
    }
}
