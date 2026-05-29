package uk.ac.ebi.zooma2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;

/**
 * Composite ranking signal for {@link MapResult}s.
 *
 * <p>Each matcher (lexical, semantic embedding, curated, cross-reference, …)
 * produces its own confidence on a different effective scale. Comparing those
 * scalars directly is brittle: a 0.85 lexical "exact label" hit and a 0.85
 * embedding cosine match are not the same evidence. Reciprocal Rank Fusion
 * sidesteps the scale problem by ranking within each method and summing
 * {@code 1 / (k + rank)}. A term that appears near the top in multiple
 * methods accumulates more fused score than one that wins only in a single
 * method.
 *
 * <p>When {@code targetOntologies} is set on the filter, a small additive
 * priority bonus is also applied — proportional to position in the list —
 * so that on close ties the higher-priority ontology wins, while a clearly
 * stronger lower-priority hit can still outrank it.
 *
 * <p>This step writes to {@link MapResult#rankingScore} and does not modify
 * {@link MapResult#mappingConfidence}.
 */
public final class RankFusion {

    /** RRF constant — standard choice from Cormack et al. 2009. */
    private static final double RRF_K = 60.0;

    /**
     * Maximum priority bonus added to {@link MapResult#rankingScore} for the
     * highest-priority ontology. Kept small so a clearly stronger lower-priority
     * match (e.g. exact label at rank 1 vs synonym at rank 3) still wins.
     *
     * <p>For context, the top RRF contribution from a single method is
     * {@code 1/61 ≈ 0.0164}, so a 0.02 bonus is meaningful but not dominant.
     */
    private static final double PRIORITY_BONUS_MAX = 0.02;

    private RankFusion() {}

    /**
     * Compute and assign {@link MapResult#rankingScore} for each result.
     *
     * @param results results to score in place (one MapResult per matcher hit;
     *                may contain multiple entries for the same termID from
     *                different methods)
     * @param filter  optional filter; only {@code targetOntologies} (ordered)
     *                is used by this step
     */
    public static void apply(List<MapResult> results, Filter filter) {
        if (results == null || results.isEmpty()) return;

        // Reset any pre-existing ranking score (defensive — keeps the function pure).
        for (var r : results) r.rankingScore = 0.0;

        // Group non-error results by matcher method, preserving deterministic iteration.
        Map<String, List<MapResult>> byMethod = new LinkedHashMap<>();
        for (var r : results) {
            if (r.error != null) continue;
            String method = methodOf(r);
            byMethod.computeIfAbsent(method, k -> new ArrayList<>()).add(r);
        }

        // RRF expects each retriever to be a *deduped* ranked list. Within a
        // single matcher method the same term can surface multiple times (e.g.
        // a lexical method covers both OLS tag_text and fuzzy lexical search),
        // so dedupe per (method, term) keeping the highest confidence before
        // ranking. Otherwise a term that surfaces in multiple sub-paths of one
        // method accumulates several contributions and dominates fusion.
        Map<String, Double> fusedByTerm = new HashMap<>();
        for (var group : byMethod.values()) {
            Map<String, MapResult> bestPerTerm = new LinkedHashMap<>();
            for (var r : group) {
                String key = termKey(r);
                if (key == null) continue;
                var existing = bestPerTerm.get(key);
                if (existing == null || r.mappingConfidence > existing.mappingConfidence) {
                    bestPerTerm.put(key, r);
                }
            }
            List<MapResult> deduped = new ArrayList<>(bestPerTerm.values());
            deduped.sort(Comparator.<MapResult>comparingDouble(r -> r.mappingConfidence).reversed());
            for (int i = 0; i < deduped.size(); i++) {
                String key = termKey(deduped.get(i));
                fusedByTerm.merge(key, 1.0 / (RRF_K + (i + 1)), Double::sum);
            }
        }
        for (var r : results) {
            if (r.error != null) continue;
            String key = termKey(r);
            if (key != null) {
                r.rankingScore = fusedByTerm.getOrDefault(key, 0.0);
            }
        }

        applyPriorityBonus(results, filter);
    }

    /** Identity key for grouping MapResults that refer to the same ontology term. */
    private static String termKey(MapResult r) {
        if (r.ontologyTermID != null) return r.ontologyTermID.toLowerCase(Locale.ROOT);
        return null;
    }

    /**
     * Add a small additive bonus based on position in {@code targetOntologies}.
     * No-op if the filter doesn't specify an ordered ontology list.
     */
    private static void applyPriorityBonus(List<MapResult> results, Filter filter) {
        if (filter == null || filter.targetOntologies == null || filter.targetOntologies.isEmpty()) {
            return;
        }
        int n = filter.targetOntologies.size();
        if (n < 2) return; // a single target ontology has no relative priority

        Map<String, Integer> priority = new HashMap<>();
        for (int i = 0; i < n; i++) {
            priority.put(filter.targetOntologies.get(i).toLowerCase(Locale.ROOT), i);
        }

        for (var r : results) {
            if (r.error != null) continue;
            String onto = ontologyPrefix(r);
            if (onto == null) continue;
            Integer idx = priority.get(onto);
            if (idx == null) continue;
            // Bonus is largest for index 0 (highest priority) and shrinks linearly.
            double bonus = PRIORITY_BONUS_MAX * (n - idx) / (double) n;
            r.rankingScore += bonus;
        }
    }

    private static String methodOf(MapResult r) {
        if (r.mappingProvenance == null || r.mappingProvenance.isEmpty()) {
            return "unknown";
        }
        String m = r.mappingProvenance.get(0).method;
        return m != null ? m : "unknown";
    }

    /**
     * Extract the ontology prefix the same way {@link Deduplicator} does so
     * priority lookups stay consistent across the two steps.
     */
    private static String ontologyPrefix(MapResult r) {
        if (r.ontologyTermID != null && r.ontologyTermID.contains(":")) {
            return r.ontologyTermID.split(":")[0].toLowerCase(Locale.ROOT);
        }
        return r.ontologyURI != null ? r.ontologyURI.toLowerCase(Locale.ROOT) : null;
    }
}
