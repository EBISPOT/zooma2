package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.util.RequestCancellation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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

        List<String> allTerms = properties.stream()
            .map(p -> p.textToMap)
            .filter(v -> v != null && !v.isEmpty())
            .collect(Collectors.toList());
        var tagTextResults = textTaggerService.bulkTagText(allTerms);

        return properties.stream()
            .parallel()
            .flatMap(s -> {
                var taggerAnnotations = tagTextResults.getOrDefault(s.textToMap, List.of());
                if (!returnAll && textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, sources)) {
                    var results = stringMapper.annotationsToMapResults(taggerAnnotations, s, Boolean.TRUE.equals(deep));
                    var deduped = deduplicator.deduplicate(results, sources, excludeTermIds);
                    return deduped.stream();
                }
                var results = stringMapper.mapOne(s, sources, model, deep);
                if (!taggerAnnotations.isEmpty()) {
                    var taggerResults = stringMapper.annotationsToMapResults(taggerAnnotations, s, Boolean.TRUE.equals(deep));
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
     * Processes a batch of strings in parallel using virtual threads, calling
     * {@code onPropertyMapped} as each string completes.
     *
     * <p>Pass 1: all strings run with shallow-only search (auto-escalation suppressed).
     * Strings where the shallow pass found no target-ontology result are deferred to Pass 2.
     * Pass 2: deferred strings run with full deep search.
     */
    public void mapEach(List<StringToMap> properties, Filter filter, String model,
                        BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {
        mapEach(properties, filter, model, null, false, null, onPropertyMapped);
    }

    public void mapEach(List<StringToMap> properties, Filter filter, String model,
                        List<String> excludeTermIds, boolean returnAll, Boolean deep,
                        BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {
        List<String> allTerms = properties.stream()
            .map(p -> p.textToMap)
            .filter(v -> v != null && !v.isEmpty())
            .collect(Collectors.toList());
        Map<String, List<Annotation>> tagTextResults = textTaggerService.bulkTagText(allTerms);
        System.err.println("Bulk tag_text returned matches for " + tagTextResults.size() + "/" + allTerms.size() + " terms");

        if (returnAll || deep != null) {
            mapEachSinglePass(properties, tagTextResults, filter, model, excludeTermIds, returnAll, deep, onPropertyMapped);
            return;
        }

        List<StringToMap> needsDeep = shallowAnnotator.runPass(
            properties, tagTextResults, filter, model, excludeTermIds, onPropertyMapped
        );
        if (needsDeep.isEmpty()) return;
        System.err.println("Running deep search for " + needsDeep.size() + " properties after shallow pass");
        deepAnnotator.runPass(needsDeep, tagTextResults, filter, model, excludeTermIds, onPropertyMapped);
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

        var cancelled = RequestCancellation.newFlag();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = properties.stream().map(prop ->
                executor.submit(() -> {
                    RequestCancellation.setFlag(cancelled);
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                        var taggerAnnotations = tagTextResults.getOrDefault(prop.textToMap, List.of());
                        if (!returnAll && textTaggerService.hasFullMatchFromTargetOntologies(taggerAnnotations, filter)) {
                            var results = stringMapper.annotationsToMapResults(taggerAnnotations, prop, Boolean.TRUE.equals(deep));
                            onPropertyMapped.accept(prop, deduplicateForMode(results, filter, excludeTermIds, returnAll));
                            return;
                        }

                        List<MapResult> results = stringMapper.mapOne(prop, filter, model, deep);
                        if (!taggerAnnotations.isEmpty()) {
                            results.addAll(stringMapper.annotationsToMapResults(taggerAnnotations, prop, Boolean.TRUE.equals(deep)));
                        }
                        onPropertyMapped.accept(prop, deduplicateForMode(results, filter, excludeTermIds, returnAll));
                    } catch (java.io.UncheckedIOException e) {
                        throw e;
                    } catch (Exception e) {
                        System.err.println("Error mapping property '" + prop.textToMap + "': " + e.getMessage());
                        onPropertyMapped.accept(prop, List.of(MapResult.error(prop.textToMap, prop.propertyType, e.getMessage())));
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
        } finally {
            RequestCancellation.clearFlag();
        }
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
