package uk.ac.ebi.zooma2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/**
 * Centralised deduplication and filtering of mapping results.
 *
 * All rules are applied in order:
 *   1. Filter by allowed ontologies (if an ontology filter is active)
 *   2. Suppress curated-embedding results when a curated-exact result exists
 *   3. Among embedding results, keep only the best match (ties allowed)
 *   4. Drop results much weaker than the best match
 *   5. Deduplicate by ontologyTermID, keeping the highest confidence
 */
public class Deduplicator {

    private final PrefixMap prefixMap;

    public Deduplicator(PrefixMap prefixMap) {
        this.prefixMap = prefixMap;
    }

    /**
     * Apply all dedup/filter rules to a list of MapResults for a single term.
     */
    public List<MapResult> deduplicate(List<MapResult> results, Filter filter) {
        return deduplicate(results, filter, null);
    }

    /**
     * Apply all dedup/filter rules, optionally excluding specific term IDs.
     */
    public List<MapResult> deduplicate(List<MapResult> results, Filter filter, List<String> excludeTermIds) {
        // 0. Exclude rejected term IDs (thumbs-down)
        excludeTerms(results, excludeTermIds);

        // 1. Filter by ontology
        filterByOntologies(results, filter);

        // 2. If any curated-exact result exists, drop curated-embedding results
        suppressEmbeddingIfExactExists(results);

        // 3. Among embedding results, keep only the best match
        keepBestEmbeddingResult(results);

        // 4. Drop results much weaker than the best match
        suppressWeakResults(results);

        // 5. Deduplicate by term ID, keeping highest confidence
        return deduplicateByTermId(results);
    }

    // ---- individual rules (package-visible for testing) ----

    void excludeTerms(List<MapResult> results, List<String> excludeTermIds) {
        if (excludeTermIds == null || excludeTermIds.isEmpty()) return;
        Set<String> excluded = excludeTermIds.stream()
            .map(id -> prefixMap.shortFormToIri(id))
            .collect(Collectors.toSet());
        results.removeIf(r -> {
            if (r.ontologyTermID == null) return false;
            return excluded.contains(prefixMap.shortFormToIri(r.ontologyTermID));
        });
    }

    void filterByOntologies(List<MapResult> results, Filter filter) {
        if (filter == null || filter.ontologies == null || filter.ontologies.isEmpty()) {
            return;
        }
        Set<String> allowed = filter.ontologies.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        results.removeIf(r -> {
            String onto = r.ontologyURI;
            if (onto == null && r.ontologyTermID != null && r.ontologyTermID.contains(":")) {
                onto = r.ontologyTermID.split(":")[0];
            }
            if (onto == null) return true;
            return !allowed.contains(onto.toLowerCase());
        });
    }

    void suppressEmbeddingIfExactExists(List<MapResult> results) {
        boolean hasExact = results.stream()
            .anyMatch(r -> isCuratedExact(r));
        if (hasExact) {
            results.removeIf(r -> isEmbeddingResult(r));
        }
    }

    /**
     * Among embedding (method="semantic") results, keep only the single best
     * match. Embedding search is inherently fuzzy; near-miss scores (e.g. 0.83
     * vs 0.82) do not indicate genuinely different valid mappings.
     */
    void keepBestEmbeddingResult(List<MapResult> results) {
        double bestEmbedding = results.stream()
            .filter(Deduplicator::isEmbeddingResult)
            .mapToDouble(r -> r.mappingConfidence)
            .max()
            .orElse(0.0);
        if (bestEmbedding > 0) {
            results.removeIf(r -> isEmbeddingResult(r)
                && r.mappingConfidence < bestEmbedding - 0.005);
        }
    }

    /**
     * If the best result is significantly stronger than weaker results,
     * drop the weak ones. Keeps results within 0.2 of the best confidence.
     */
    void suppressWeakResults(List<MapResult> results) {
        double best = results.stream()
            .mapToDouble(r -> r.mappingConfidence)
            .max()
            .orElse(0.0);
        if (best >= 0.7) {
            results.removeIf(r -> r.mappingConfidence < best - 0.2);
        }
    }

    List<MapResult> deduplicateByTermId(List<MapResult> results) {
        LinkedHashMap<String, MapResult> best = new LinkedHashMap<>();
        for (var r : results) {
            String key = r.ontologyTermID;
            if (key == null) continue;
            key = prefixMap.shortFormToIri(key);
            MapResult existing = best.get(key);
            if (existing == null || r.mappingConfidence > existing.mappingConfidence) {
                best.put(key, r);
            }
        }
        return new ArrayList<>(best.values());
    }

    // ---- helpers ----

    private static boolean isCuratedExact(MapResult r) {
        if (r.mappingProvenance == null || r.mappingProvenance.isEmpty()) return false;
        var step = r.mappingProvenance.get(0);
        return "curated".equals(step.method);
    }

    private static boolean isEmbeddingResult(MapResult r) {
        if (r.mappingProvenance == null || r.mappingProvenance.isEmpty()) return false;
        return "semantic".equals(r.mappingProvenance.get(0).method);
    }
}
