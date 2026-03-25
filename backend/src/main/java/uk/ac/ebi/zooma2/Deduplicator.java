package uk.ac.ebi.zooma2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        // 0b. Filter by required datasources
        filterByRequiredDatasources(results, filter);

        // 1. Filter by ontology
        filterByOntologies(results, filter);

        // 2. If any curated-exact result exists, drop curated-embedding results
        suppressEmbeddingIfExactExists(results);

        // 3. Among embedding results, keep only the best match
        keepBestEmbeddingResult(results);

        // 4. Drop results much weaker than the best match
        suppressWeakResults(results, filter);

        // 5. Deduplicate by term ID, keeping highest confidence
        var deduped = deduplicateByTermId(results);

        // 6. When target ontologies set, keep only the best result per ontology
        keepBestPerTargetOntology(deduped, filter);

        // 7. Among remaining results, remove lower-priority ontology results
        keepHigherPriorityOnTie(deduped, filter);

        return deduped;
    }

    /**
     * Light deduplication: only exclude rejected terms, filter by ontology,
     * and deduplicate by term ID. Skips suppression of weak/embedding results
     * so that all viable candidates are returned.
     */
    public List<MapResult> deduplicateLight(List<MapResult> results, Filter filter, List<String> excludeTermIds) {
        excludeTerms(results, excludeTermIds);
        filterByRequiredDatasources(results, filter);
        filterByOntologies(results, filter);
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
        if (filter == null || filter.includeOtherOntologies || filter.targetOntologies == null || filter.targetOntologies.isEmpty()) {
            return;
        }
        Set<String> allowed = filter.targetOntologies.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        results.removeIf(r -> {
            if (r.error != null) return false; // preserve error results
            String onto = getOntologyPrefix(r);
            if (onto == null) return true;
            return !allowed.contains(onto);
        });
    }

    void filterByRequiredDatasources(List<MapResult> results, Filter filter) {
        if (filter == null || filter.required == null || filter.required.isEmpty()) {
            return;
        }
        Set<String> allowed = filter.required.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        // Only filter curated results by datasource. Lexical and embedding
        // results come from ontologies (datasource = ontology name) and should
        // not be restricted by the DATABASE-level required filter.
        results.removeIf(r -> {
            if (r.error != null) return false;
            if (!isCuratedExact(r)) return false;
            String ds = r.datasource;
            if (ds == null) return true;
            return !allowed.contains(ds.toLowerCase(Locale.ROOT));
        });
    }

    /**
     * When target ontologies are set, keep only the single best result per
     * target ontology. Multiple results from the same ontology are redundant
     * when the user has specified which ontologies they care about.
     */
    void keepBestPerTargetOntology(List<MapResult> results, Filter filter) {
        if (filter == null || filter.targetOntologies == null || filter.targetOntologies.isEmpty()) {
            return;
        }
        Set<String> targets = filter.targetOntologies.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        // Find best confidence per target ontology
        Map<String, Double> bestPerOntology = new HashMap<>();
        for (var r : results) {
            String onto = getOntologyPrefix(r);
            if (onto == null || !targets.contains(onto)) continue;
            bestPerOntology.merge(onto, r.mappingConfidence, Math::max);
        }

        results.removeIf(r -> {
            String onto = getOntologyPrefix(r);
            if (onto == null || !targets.contains(onto)) return false;
            return r.mappingConfidence < bestPerOntology.get(onto);
        });
    }

    /**
     * Among results whose ontologies are both in the target list, remove
     * lower-priority ontology results when a higher-priority ontology
     * result exists with an equal or better score.
     */
    void keepHigherPriorityOnTie(List<MapResult> results, Filter filter) {
        if (filter == null || filter.targetOntologies == null || filter.targetOntologies.size() < 2) {
            return;
        }
        // Build priority map: lower index = higher priority
        Map<String, Integer> priority = new HashMap<>();
        for (int i = 0; i < filter.targetOntologies.size(); i++) {
            priority.put(filter.targetOntologies.get(i).toLowerCase(Locale.ROOT), i);
        }

        // For each target-ontology result, check if a higher-priority
        // target-ontology result exists with equal or better confidence
        Set<MapResult> toRemove = new java.util.HashSet<>();
        for (var r : results) {
            String onto = getOntologyPrefix(r);
            if (onto == null || !priority.containsKey(onto)) continue;
            int myPriority = priority.get(onto);

            for (var other : results) {
                if (other == r) continue;
                String otherOnto = getOntologyPrefix(other);
                if (otherOnto == null || !priority.containsKey(otherOnto)) continue;
                int otherPriority = priority.get(otherOnto);

                // If a higher-priority result exists with equal or better score, remove this one
                if (otherPriority < myPriority
                        && other.mappingConfidence >= r.mappingConfidence) {
                    toRemove.add(r);
                    break;
                }
            }
        }
        results.removeAll(toRemove);
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
     * drop the weak ones. When target ontologies are set, use a tighter
     * threshold (0.05) so near-misses from other ontologies are suppressed.
     * Otherwise keep results within 0.2 of the best.
     */
    void suppressWeakResults(List<MapResult> results, Filter filter) {
        double best = results.stream()
            .mapToDouble(r -> r.mappingConfidence)
            .max()
            .orElse(0.0);
        boolean hasTargetOntologies = filter != null && filter.targetOntologies != null && !filter.targetOntologies.isEmpty();
        double gap = hasTargetOntologies ? 0.05 : 0.2;
        if (best >= 0.7) {
            results.removeIf(r -> r.mappingConfidence < best - gap);
        }
    }

    List<MapResult> deduplicateByTermId(List<MapResult> results) {
        LinkedHashMap<String, MapResult> best = new LinkedHashMap<>();
        List<MapResult> errorResults = new ArrayList<>();
        for (var r : results) {
            if (r.error != null) { errorResults.add(r); continue; }
            String key = r.ontologyTermID;
            if (key == null) continue;
            key = prefixMap.shortFormToIri(key);
            MapResult existing = best.get(key);
            if (existing == null || r.mappingConfidence > existing.mappingConfidence) {
                best.put(key, r);
            }
        }
        List<MapResult> result = new ArrayList<>(best.values());
        result.addAll(errorResults);
        return result;
    }

    // ---- helpers ----

    private static String getOntologyPrefix(MapResult r) {
        // Prefer the term ID prefix (e.g. "EFO" from "EFO:0000699") over ontologyURI,
        // because ontologyURI comes from OLS's ontology_name which reflects the ontology
        // file that contained the term — not necessarily the term's own ontology.
        // e.g. MONDO imports EFO terms, so EFO:0000699 resolved from MONDO has
        // ontology_name="mondo", which would misidentify it.
        if (r.ontologyTermID != null && r.ontologyTermID.contains(":")) {
            return r.ontologyTermID.split(":")[0].toLowerCase(Locale.ROOT);
        }
        return r.ontologyURI != null ? r.ontologyURI.toLowerCase(Locale.ROOT) : null;
    }

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
