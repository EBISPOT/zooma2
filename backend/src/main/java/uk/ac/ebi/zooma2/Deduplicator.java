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
import uk.ac.ebi.zooma2.rules.RuleContext;
import uk.ac.ebi.zooma2.rules.RuleEngine;

/**
 * Centralised deduplication and filtering of mapping results.
 *
 * <p>Rules are applied in order:
 *   1. Exclude rejected (thumbs-down) terms and required-datasource filter
 *   2. Filter by allowed ontologies (if an ontology filter is active)
 *   3. Assign each result a composite {@link RankFusion} score combining
 *      Reciprocal-Rank-Fusion across matchers with a small ontology-priority bonus
 *   4. Suppress curated-embedding results when a curated-exact result exists
 *   5. Among embedding results, keep only the best match (ties allowed)
 *   6. Drop results much weaker than the best match
 *   7. Deduplicate by ontologyTermID, keeping the result with the highest
 *      rankingScore (mappingConfidence as tiebreak)
 *   8. Optionally collapse to one result per target ontology (opt-in via
 *      {@code filter.limitPerOntology})
 */
public class Deduplicator {

    private final PrefixMap prefixMap;
    private final RuleEngine ruleEngine;

    public Deduplicator(PrefixMap prefixMap) {
        this(prefixMap, RuleEngine.empty());
    }

    public Deduplicator(PrefixMap prefixMap, RuleEngine ruleEngine) {
        this.prefixMap = prefixMap;
        this.ruleEngine = ruleEngine != null ? ruleEngine : RuleEngine.empty();
    }

    /**
     * Apply all dedup/filter rules to a list of MapResults for a single term.
     */
    public List<MapResult> deduplicate(List<MapResult> results, Filter filter) {
        return deduplicate(results, filter, null, null);
    }

    /**
     * Apply all dedup/filter rules, optionally excluding specific term IDs.
     */
    public List<MapResult> deduplicate(List<MapResult> results, Filter filter, List<String> excludeTermIds) {
        return deduplicate(results, filter, excludeTermIds, null);
    }

    /**
     * Apply all dedup/filter rules, optionally excluding specific term IDs and
     * applying the declarative GATE rules for the supplied {@link RuleContext}.
     */
    public List<MapResult> deduplicate(List<MapResult> results, Filter filter, List<String> excludeTermIds, RuleContext ruleCtx) {
        excludeTerms(results, excludeTermIds);
        filterByRequiredDatasources(results, filter);
        filterByOntologies(results, filter);

        // GATE: drop candidates that violate domain rules, before fusion/ranking
        // so rejected terms never influence the composite score.
        applyGate(results, ruleCtx);

        // Compute composite rankingScore (RRF across matchers + ontology-priority
        // bonus). Must run before any step that compares cross-method scores.
        RankFusion.apply(results, filter);

        // SCORE / SELECT: rule-driven bonuses/penalties and ontology-preference
        // nudges, composed on top of the fusion score.
        applyScore(results, ruleCtx);
        applySelect(results, ruleCtx);

        suppressEmbeddingIfExactExists(results);
        keepBestEmbeddingResult(results);
        suppressWeakResults(results, filter);

        var deduped = deduplicateByTermId(results);

        // Opt-in: collapse to a single best result per target ontology.
        keepBestPerTargetOntology(deduped, filter);

        return deduped;
    }

    /**
     * Light deduplication: only exclude rejected terms, filter by ontology,
     * and deduplicate by term ID. Skips suppression of weak/embedding results
     * so that all viable candidates are returned.
     */
    public List<MapResult> deduplicateLight(List<MapResult> results, Filter filter, List<String> excludeTermIds) {
        return deduplicateLight(results, filter, excludeTermIds, null);
    }

    public List<MapResult> deduplicateLight(List<MapResult> results, Filter filter, List<String> excludeTermIds, RuleContext ruleCtx) {
        excludeTerms(results, excludeTermIds);
        filterByRequiredDatasources(results, filter);
        filterByOntologies(results, filter);
        // GATE still applies in the light path: forbidden terms must not leak just
        // because the caller asked for the full candidate set.
        applyGate(results, ruleCtx);
        // Stamp rankingScore even in the light path so callers that consume the
        // candidate set can re-rank deterministically.
        RankFusion.apply(results, filter);
        applyScore(results, ruleCtx);
        applySelect(results, ruleCtx);
        return deduplicateByTermId(results);
    }

    // ---- individual rules (package-visible for testing) ----

    /**
     * GATE: remove candidates rejected by the declarative rule engine. No-op when
     * no rule context is supplied or the engine has no rulesets. Error results are
     * preserved so failures still surface to the caller.
     */
    void applyGate(List<MapResult> results, RuleContext ruleCtx) {
        if (ruleCtx == null || ruleEngine == null || ruleEngine.isEmpty()) return;
        results.removeIf(r -> {
            if (r.error != null) return false;
            boolean reject = ruleEngine.shouldReject(ruleCtx, r);
            if (reject) {
                System.err.println("GATE rejected " + r.ontologyTermID + " for '"
                    + ruleCtx.originalText() + "': " + ruleCtx.rejectReason());
            }
            return reject;
        });
    }

    /** Largest ranking-score nudge a SELECT ontology-preference rule can add. */
    private static final double SELECT_BONUS_MAX = 0.01;

    /**
     * SCORE: add each candidate's net rule-driven score delta to its rankingScore.
     * No-op when no rule context is supplied or the engine has no rulesets.
     */
    void applyScore(List<MapResult> results, RuleContext ruleCtx) {
        if (ruleCtx == null || ruleEngine.isEmpty()) return;
        for (MapResult r : results) {
            if (r.error != null) continue;
            r.rankingScore += ruleEngine.scoreDelta(ruleCtx, r);
        }
    }

    /**
     * SELECT: nudge rankingScore by ontology preference. A declared order such as
     * [efo, mondo, hp, oba] adds a small, rank-scaled bonus so that — all else
     * equal — preferred ontologies sort first. Composes with the existing
     * {@link RankFusion} priority bonus rather than replacing it.
     */
    void applySelect(List<MapResult> results, RuleContext ruleCtx) {
        if (ruleCtx == null || ruleEngine.isEmpty()) return;
        List<String> order = ruleEngine.selectOntologyOrder(ruleCtx);
        if (order == null || order.isEmpty()) return;
        List<String> lower = order.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
        int n = lower.size();
        for (MapResult r : results) {
            if (r.error != null) continue;
            String onto = getOntologyPrefix(r);
            int idx = onto == null ? -1 : lower.indexOf(onto);
            if (idx >= 0) {
                r.rankingScore += SELECT_BONUS_MAX * (double) (n - idx) / n;
            }
        }
    }

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
     * Opt-in: when {@code filter.limitPerOntology} is true and target ontologies
     * are set, keep only the single best result per target ontology. "Best" is
     * measured by composite {@link MapResult#rankingScore}, with
     * {@code mappingConfidence} as tiebreak.
     *
     * <p>By default ({@code limitPerOntology=false}) this is a no-op so callers
     * that want the full candidate set for downstream re-ranking get every
     * unique term back. Cross-ontology priority is already encoded in
     * rankingScore by {@link RankFusion}, so no separate tie-break pass is
     * needed at this stage.
     */
    void keepBestPerTargetOntology(List<MapResult> results, Filter filter) {
        if (filter == null || !filter.limitPerOntology) {
            return;
        }
        if (filter.targetOntologies == null || filter.targetOntologies.isEmpty()) {
            return;
        }
        Set<String> targets = filter.targetOntologies.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        // Find best (rankingScore, mappingConfidence) per target ontology.
        Map<String, double[]> bestPerOntology = new HashMap<>();
        for (var r : results) {
            String onto = getOntologyPrefix(r);
            if (onto == null || !targets.contains(onto)) continue;
            double[] cur = bestPerOntology.get(onto);
            if (cur == null || r.rankingScore > cur[0]
                    || (r.rankingScore == cur[0] && r.mappingConfidence > cur[1])) {
                bestPerOntology.put(onto, new double[]{r.rankingScore, r.mappingConfidence});
            }
        }

        results.removeIf(r -> {
            String onto = getOntologyPrefix(r);
            if (onto == null || !targets.contains(onto)) return false;
            double[] best = bestPerOntology.get(onto);
            if (r.rankingScore < best[0]) return true;
            if (r.rankingScore > best[0]) return false;
            return r.mappingConfidence < best[1];
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
            // Prefer the survivor with the highest raw matcher confidence — that's
            // what the API reports. rankingScore is already term-aggregated by
            // RankFusion, so it is the same across all per-method entries for
            // a given term and can't be used to pick between them.
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
