package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.util.RequestCancellation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrates mapping for a batch of strings.
 *
 * <p>{@link #mapAll} processes all strings in parallel and waits for all results.
 * {@link #mapEach} uses a two-pass strategy: all shallow mappings complete first,
 * then a second pass runs deep search for any strings that need it. This lets the
 * caller stream results progressively while still guaranteeing that cheap work
 * finishes before expensive deep searches begin.
 */
public class BatchMapper {

    private final StringMapper stringMapper;
    private final OlsTextTaggerMatcher textTaggerService;
    private final Deduplicator deduplicator;
    private final ZoomaAnnotatorShallow shallowAnnotator;
    private final ZoomaAnnotatorDeep deepAnnotator;

    public BatchMapper(StringMapper stringMapper, OlsTextTaggerMatcher textTaggerService, Deduplicator deduplicator) {
        this.stringMapper = stringMapper;
        this.textTaggerService = textTaggerService;
        this.deduplicator = deduplicator;
        this.shallowAnnotator = new ZoomaAnnotatorShallow(stringMapper, textTaggerService, deduplicator);
        this.deepAnnotator = new ZoomaAnnotatorDeep(stringMapper, deduplicator);
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

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model,
                                        List<String> excludeTermIds, boolean returnAll) {
        return mapAll(stringsToMap, sources, model, excludeTermIds, returnAll, null);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model,
                                        List<String> excludeTermIds, boolean returnAll, Boolean deep) {
        List<StringToMap> properties = stringsToMap.collect(Collectors.toList());

        // Tag each distinct text once: the tagger keys its results by text, so a
        // repeated text (e.g. the same value under two property types) would only
        // duplicate the hits, and would change the request body, defeating the cache.
        List<String> allTerms = properties.stream()
            .map(p -> p.textToMap)
            .filter(v -> v != null && !v.isEmpty())
            .distinct()
            .collect(Collectors.toList());
        var tagged = textTaggerService.bulkTag(allTerms);
        var tagTextResults = tagged.byTerm;

        // Virtual threads, not a parallel stream: the work is blocking OLS I/O, which on
        // the CPU-sized common ForkJoinPool caps concurrency and lets one large request
        // starve every other request's parallel work. Each property is contained: a
        // failure yields an error result for that property, not a 500 for the request.
        final java.util.concurrent.atomic.AtomicBoolean cancelFlag = RequestCancellation.getFlag();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<MapResult>>> futures = properties.stream().map(s ->
                executor.submit(() -> {
                    if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                    try {
                        var taggerAnnotations = tagTextResults.getOrDefault(s.textToMap, List.of());
                        if (!returnAll && textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, sources, excludeTermIds)) {
                            var results = stringMapper.annotationsToMapResults(taggerAnnotations, s, Boolean.TRUE.equals(deep));
                            return deduplicator.deduplicate(results, sources, excludeTermIds);
                        }
                        var results = stringMapper.map(s, taggerAnnotations, sources, model, deep, excludeTermIds);
                        return withTaggerWarning(tagged, s, deduplicateForMode(results, sources, excludeTermIds, returnAll));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error mapping property '" + s.textToMap + "': " + MapResult.describe(e));
                        e.printStackTrace();
                        return List.of(MapResult.error(s.textToMap, s.propertyType, MapResult.describe(e)));
                    }
                })
            ).collect(Collectors.toList());

            List<MapResult> all = new ArrayList<>();
            for (var future : futures) {
                try {
                    all.addAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    throw new RuntimeException("Interrupted while mapping", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof java.io.UncheckedIOException) {
                        futures.forEach(f -> f.cancel(true));
                        throw (java.io.UncheckedIOException) e.getCause();
                    }
                    throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
                }
            }
            return all;
        }
    }

    /**
     * Processes a batch of strings in parallel using virtual threads, calling
     * {@code onPropertyMapped} as each string completes.
     *
     * <p>Pass 1: all strings run the shallow search. Strings for which the escalation
     * policy asks for Phase 2 are deferred. Pass 2: the deferred runs are completed
     * with the deep search, seeded with their Phase-1 annotations.
     */
    public void mapEach(List<StringToMap> properties, Filter filter, String model,
                        BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {
        mapEach(properties, filter, model, null, false, null, onPropertyMapped);
    }

    public void mapEach(List<StringToMap> properties, Filter filter, String model,
                        List<String> excludeTermIds, boolean returnAll, Boolean deep,
                        BiConsumer<StringToMap, List<MapResult>> onPropertyMappedRaw) {
        // Tag each distinct text once: the tagger keys its results by text, so a
        // repeated text (e.g. the same value under two property types) would only
        // duplicate the hits, and would change the request body, defeating the cache.
        List<String> allTerms = properties.stream()
            .map(p -> p.textToMap)
            .filter(v -> v != null && !v.isEmpty())
            .distinct()
            .collect(Collectors.toList());
        var tagged = textTaggerService.bulkTag(allTerms);
        Map<String, List<Annotation>> tagTextResults = tagged.byTerm;
        System.err.println("Bulk tag_text returned matches for " + tagTextResults.size() + "/" + allTerms.size() + " terms");
        // A failed tag_text chunk silently strips the exact-match tier from its
        // properties; tell them so a degraded answer is not read as "no match".
        final BiConsumer<StringToMap, List<MapResult>> onPropertyMapped = withTaggerWarnings(tagged, onPropertyMappedRaw);

        if (returnAll || deep != null) {
            mapEachSinglePass(properties, tagTextResults, filter, model, excludeTermIds, returnAll, deep, onPropertyMapped);
            return;
        }

        List<StringMapper.MappingRun> needsDeep = shallowAnnotator.runPass(
            properties, tagTextResults, filter, model, excludeTermIds, onPropertyMapped
        );
        if (needsDeep.isEmpty()) return;
        // Deferred properties never reach the callback that aborts on disconnect, so a
        // client that left during the shallow pass would otherwise get a deep pass run
        // (and, before the passes shared one flag, an uncancellable one) on its behalf.
        if (RequestCancellation.isCancelled()) {
            System.err.println("Skipping deep search for " + needsDeep.size() + " properties: request cancelled");
            return;
        }
        System.err.println("Running deep search for " + needsDeep.size() + " properties after shallow pass");
        deepAnnotator.runPass(needsDeep, filter, excludeTermIds, onPropertyMapped);
    }

    private void mapEachSinglePass(
            List<StringToMap> properties,
            Map<String, List<Annotation>> tagTextResults,
            Filter filter,
            String model,
            List<String> excludeTermIds,
            boolean returnAll,
            Boolean deep,
            BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {

        try (var scope = RequestCancellation.acquire();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancelled = scope.flag();
            var futures = properties.stream().map(prop ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                        var taggerAnnotations = tagTextResults.getOrDefault(prop.textToMap, List.of());
                        if (!returnAll && textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, filter, excludeTermIds)) {
                            var results = stringMapper.annotationsToMapResults(taggerAnnotations, prop, Boolean.TRUE.equals(deep));
                            onPropertyMapped.accept(prop, deduplicateForMode(results, filter, excludeTermIds, returnAll));
                            return;
                        }

                        List<MapResult> results = stringMapper.map(prop, taggerAnnotations, filter, model, deep, excludeTermIds);
                        onPropertyMapped.accept(prop, deduplicateForMode(results, filter, excludeTermIds, returnAll));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error mapping property '" + prop.textToMap + "': " + MapResult.describe(e));
                        e.printStackTrace();
                        onPropertyMapped.accept(prop, List.of(MapResult.error(prop.textToMap, prop.propertyType, MapResult.describe(e))));
                    }
                })
            ).collect(Collectors.toList());

            for (var future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    if (e.getCause() instanceof java.io.UncheckedIOException) {
                        cancelled.set(true);
                        futures.forEach(f -> f.cancel(true));
                        throw (java.io.UncheckedIOException) e.getCause();
                    }
                    System.err.println("Error mapping property: " + e.getMessage());
                }
            }
        }
    }

    /** Wraps the completion callback so properties whose tag_text chunk failed carry a warning. */
    private static BiConsumer<StringToMap, List<MapResult>> withTaggerWarnings(
            OlsTextTaggerMatcher.TaggerResults tagged, BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {
        if (tagged.failedTerms.isEmpty()) return onPropertyMapped;
        return (prop, results) -> onPropertyMapped.accept(prop, withTaggerWarning(tagged, prop, results));
    }

    private static List<MapResult> withTaggerWarning(OlsTextTaggerMatcher.TaggerResults tagged, StringToMap prop, List<MapResult> results) {
        if (!tagged.failedTerms.contains(prop.textToMap)) return results;
        List<MapResult> withWarning = new ArrayList<>(results);
        withWarning.add(MapResult.warning(prop.textToMap, prop.propertyType,
            "OLS text tagger unavailable" + (tagged.failure != null ? ": " + tagged.failure : "")
            + "; exact and curated matches may be missing"));
        return withWarning;
    }

    private List<MapResult> deduplicateForMode(
            List<MapResult> results,
            Filter filter,
            List<String> excludeTermIds,
            boolean returnAll) {
        return returnAll
            ? deduplicator.deduplicateLight(results, filter, excludeTermIds)
            : deduplicator.deduplicate(results, filter, excludeTermIds);
    }
}
