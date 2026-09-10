package uk.ac.ebi.zooma2;

import java.util.ArrayList;
import java.util.Collections;
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
import uk.ac.ebi.zooma2.util.TermNamespace;

/**
 * Centralised deduplication and filtering of mapping results.
 *
 * All rules are applied in order:
 *   0. Exclude rejected term IDs (thumbs-down)
 *   0b. Restrict curated results to the required datasources
 *   1. Filter by allowed ontologies (if the ontology filter is hard, i.e.
 *      target ontologies set and includeOtherOntologies=false)
 *   1b. Restrict to the target ontologies' own namespaces (if definingOnly is set)
 *   1c. For organism-typed queries, prefer taxonomy (NCBITaxon) results
 *   2. Suppress curated-embedding results when a curated-exact result exists
 *   3. Among embedding results, keep only the best match (ties allowed)
 *   4. Drop weak results: anything more than 0.2 below the global best (all
 *      modes; each target ontology's own best is exempt under a hard filter),
 *      plus, when target ontologies are set, the tight 0.05 gap - applied
 *      within each target ontology under a hard filter, and only against
 *      non-target results under a soft preference (includeOtherOntologies=true)
 *   5. Deduplicate by ontologyTermID, keeping the highest confidence
 *   6. When target ontologies are set, keep only the best result per target ontology
 *   7. Among target ontologies, drop lower-priority results that tie with or
 *      lose to a higher-priority ontology's result
 */
public class Deduplicator {

    /** Confidence at or above which a match counts as lexically grounded (exact-match tier). */
    private static final double STRONG_MATCH_CONFIDENCE = 0.85;
    /** Confidence adjustment applied by the organism-type taxonomy preference. */
    private static final double TAXONOMY_PREFERENCE_ADJUSTMENT = 0.1;
    /** Weak-result suppression only engages once the best result is at least this confident. */
    private static final double WEAK_RESULT_MIN_BEST = 0.7;
    /** Results more than this far below the global best are dropped as an absolute-quality floor. */
    private static final double WEAK_RESULT_GAP = 0.2;
    /** Tight gap used when target ontologies are set: near-misses this close to a better result are redundant. */
    private static final double TARGET_ONTOLOGY_GAP = 0.05;

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

        // 1b. Restrict to the target ontologies' own namespaces if requested
        filterToDefiningNamespace(results, filter);

        // 1c. Organism-typed queries prefer taxonomy results
        preferTaxonomyForOrganismQueries(results);

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
        filterToDefiningNamespace(results, filter);
        preferTaxonomyForOrganismQueries(results);
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
     * When {@code definingOnly} is set alongside target ontologies, drop results
     * whose term does not belong to a target ontology's own namespace — i.e.
     * terms the ontology merely imports. ECTO ships its CHEBI import closure,
     * so a query filtered to {@code ecto} can otherwise return bare CHEBI
     * chemicals alongside ECTO's own exposure classes.
     *
     * <p>Namespace membership is judged by the term id's prefix (ECTO_9000460
     * → ecto, CHEBI_9937 → chebi), which under OBO conventions names the
     * defining ontology whatever file the term was found in. Terms with no
     * recognisable prefix (e.g. bare SNOMED numbers) fall back to the ontology
     * that resolved them, matching the plain ontology filter's behaviour.
     */
    void filterToDefiningNamespace(List<MapResult> results, Filter filter) {
        if (filter == null || !filter.definingOnly
                || filter.targetOntologies == null || filter.targetOntologies.isEmpty()) {
            return;
        }
        Set<String> targets = filter.targetOntologies.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        results.removeIf(r -> {
            if (r.error != null) return false;
            String prefix = TermNamespace.prefixOf(r.ontologyTermID);
            if (prefix != null) {
                return !targets.contains(prefix);
            }
            String resolvingOntology = r.ontologyURI != null ? r.ontologyURI.toLowerCase(Locale.ROOT) : null;
            return resolvingOntology == null || !targets.contains(resolvingOntology);
        });
    }

    /**
     * When the query's property type says the value is an organism (e.g.
     * "organism", "species", "genus_species"), prefer taxonomy results.
     *
     * <p>Common organism names are wildly ambiguous across ontologies: "rat" is
     * also a food product (FOODON), an NCIT concept, part of species names like
     * rat snakes, etc. When the caller has told us the value is an organism and
     * a lexically grounded NCBITaxon match exists, boost taxonomy matches and
     * demote everything else so the taxon ranks first. Without a strong taxonomy
     * match (e.g. "yeast", which NCBI Taxonomy has no synonym for) this rule
     * does nothing, so exact matches from other ontologies still win.
     */
    void preferTaxonomyForOrganismQueries(List<MapResult> results) {
        String propertyType = results.stream()
            .filter(r -> r.error == null && r.propertyType != null)
            .map(r -> r.propertyType)
            .findFirst().orElse(null);
        if (!isOrganismLikeType(propertyType)) {
            return;
        }

        boolean hasStrongTaxonomyMatch = results.stream().anyMatch(r ->
            r.error == null && isTaxonomyResult(r) && r.mappingConfidence >= STRONG_MATCH_CONFIDENCE);
        if (!hasStrongTaxonomyMatch) {
            return;
        }

        for (var r : results) {
            if (r.error != null) continue;
            if (isTaxonomyResult(r)) {
                // Only boost lexically grounded matches; a weak embedding guess from
                // NCBITaxon (e.g. a rat-snake species for "rat") earns no boost.
                if (r.mappingConfidence >= STRONG_MATCH_CONFIDENCE) {
                    r.mappingConfidence = Math.min(1.0, r.mappingConfidence + TAXONOMY_PREFERENCE_ADJUSTMENT);
                }
            } else {
                r.mappingConfidence = Math.max(0.0, r.mappingConfidence - TAXONOMY_PREFERENCE_ADJUSTMENT);
            }
        }
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
     * Drop results that are clearly weaker than the competition. Which gaps
     * apply depends on the ontology filter mode: a <em>hard filter</em> is
     * target ontologies with {@code includeOtherOntologies=false} (V2
     * {@code ontologies:[..]}, V3 {@code includeOtherOntologies:false}); a
     * <em>soft preference</em> is target ontologies with
     * {@code includeOtherOntologies=true} (V3's default), where non-target
     * results are still in the list.
     *
     * <ul>
     *   <li><b>Absolute-quality floor (all modes).</b> If the global best is at
     *   least 0.7, drop results more than 0.2 below it. Under a hard filter each
     *   target ontology's own best result is exempt: the caller explicitly asked
     *   for that ontology and {@link #keepBestPerTargetOntology} will keep
     *   exactly that result, so e.g. MONDO's best at 0.6 survives next to an
     *   EFO 1.0 when both ontologies were requested.</li>
     *   <li><b>Hard filter: tight gap within each target ontology.</b> Drop
     *   results more than 0.05 below their <em>own</em> ontology's best. A
     *   result is never measured against another ontology's best, so a weakly
     *   matching but requested ontology cannot be emptied by a strong one.</li>
     *   <li><b>Soft preference: tight gap against non-target results only.</b>
     *   Drop a non-target result when some target-ontology result scores within
     *   0.05 of it or better (FOODON 0.97 loses to EFO 1.0). Target results are
     *   subject only to the floor, so a strong non-target hit never deletes the
     *   preferred ontology's best candidate (EFO 0.85 survives FOODON 1.0). This
     *   keeps the original intent - near-misses from other ontologies are
     *   suppressed when the caller prefers X - without penalising X itself.</li>
     * </ul>
     *
     * Error results are left alone, as in the other rules.
     */
    void suppressWeakResults(List<MapResult> results, Filter filter) {
        Set<String> targets = targetOntologies(filter);
        boolean hardFilter = !targets.isEmpty() && !filter.includeOtherOntologies;
        boolean softPreference = !targets.isEmpty() && filter.includeOtherOntologies;

        double best = 0.0;
        Map<String, Double> bestPerTarget = new HashMap<>();
        for (var r : results) {
            if (r.error != null) continue;
            best = Math.max(best, r.mappingConfidence);
            String onto = getOntologyPrefix(r);
            if (onto != null && targets.contains(onto)) {
                bestPerTarget.merge(onto, r.mappingConfidence, Math::max);
            }
        }

        // Absolute-quality floor against the global best (all modes).
        if (best >= WEAK_RESULT_MIN_BEST) {
            double floor = best - WEAK_RESULT_GAP;
            results.removeIf(r -> r.error == null
                && r.mappingConfidence < floor
                && !(hardFilter && isBestOfItsTargetOntology(r, bestPerTarget)));
        }

        // Hard filter: tight gap within each target ontology, never across ontologies.
        if (hardFilter) {
            results.removeIf(r -> {
                if (r.error != null) return false;
                Double ontologyBest = bestPerTarget.get(getOntologyPrefix(r));
                return ontologyBest != null && r.mappingConfidence < ontologyBest - TARGET_ONTOLOGY_GAP;
            });
        }

        // Soft preference: the tight gap only ever penalises non-target results.
        if (softPreference && !bestPerTarget.isEmpty()) {
            double bestTarget = Collections.max(bestPerTarget.values());
            results.removeIf(r -> {
                if (r.error != null) return false;
                String onto = getOntologyPrefix(r);
                boolean isTarget = onto != null && targets.contains(onto);
                return !isTarget && bestTarget >= r.mappingConfidence - TARGET_ONTOLOGY_GAP;
            });
        }
    }

    /** Lower-cased target ontologies of the filter, or an empty set when none are set. */
    private static Set<String> targetOntologies(Filter filter) {
        if (filter == null || filter.targetOntologies == null) return Set.of();
        return filter.targetOntologies.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    }

    /** True if the result ties with the best confidence seen for its (target) ontology. */
    private static boolean isBestOfItsTargetOntology(MapResult r, Map<String, Double> bestPerTarget) {
        Double ontologyBest = bestPerTarget.get(getOntologyPrefix(r));
        return ontologyBest != null && r.mappingConfidence >= ontologyBest;
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

    /**
     * True if the property type describes a whole organism / species / taxon.
     * Anatomy-flavoured types like "organism part" or "organismPart" must not match.
     */
    static boolean isOrganismLikeType(String propertyType) {
        if (propertyType == null || propertyType.isBlank()) return false;
        Set<String> tokens = new java.util.HashSet<>(List.of(propertyType.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim()
            .split(" ")));
        boolean organismLike = tokens.contains("organism") || tokens.contains("species");
        boolean excluded = tokens.contains("part") || tokens.contains("parts")
            || tokens.contains("age") || tokens.contains("unit") || tokens.contains("disease");
        return organismLike && !excluded;
    }

    /**
     * True if the result is an NCBI Taxonomy term, whichever ontology it was
     * resolved through (EFO, MRO etc. re-expose NCBITaxon terms under their
     * original short forms).
     */
    private static boolean isTaxonomyResult(MapResult r) {
        if (r.ontologyTermID != null
                && r.ontologyTermID.toLowerCase(Locale.ROOT).startsWith("ncbitaxon")) {
            return true;
        }
        return "ncbitaxon".equals(getOntologyPrefix(r));
    }

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
