package uk.ac.ebi.zooma2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Surfaces an umbrella / least-common-ancestor candidate when the top-K
 * results all share a non-root ancestor in the same target ontology.
 *
 * <p>Motivation: a query like "Autoimmune liver disease" returns the three
 * autoimmune-hepatitis-type subtypes from MONDO; none of them is the right
 * umbrella, but their common ancestor {@code MONDO_0016264 autoimmune
 * hepatitis} is. Without this step the downstream picker rejects all
 * candidates as too narrow. This step pulls each leaf's {@code directAncestor}
 * (strict is-a closure — not partonomy) from OLS v2, intersects to find common
 * ancestors, filters out root-ish terms by {@code numHierarchicalDescendants},
 * and injects the most-specific surviving ancestor as a new candidate.
 *
 * <p>OLS calls are delegated to {@link OlsClientRepo#fetchTermsWithAncestors},
 * which reuses the existing {@link uk.ac.ebi.zooma2.repo.OlsTermCache} — so
 * repeat lookups across queries are persistent (zooma.db), not just
 * process-lifetime.
 */
public final class AncestorSurfacer {

    private static final int TOP_K = 5;
    private static final int MIN_LEAVES = 3;
    /**
     * Discard leaves below this confidence when seeding LCA computation —
     * weak lexical-substring hits (e.g. tag_text on "disease" inside "liver
     * disease") have ancestor sets that collapse the common-ancestor
     * intersection to root level.
     */
    private static final double MIN_LEAF_CONFIDENCE = 0.5;
    /**
     * Minimum Information Content (IC) for a surfaced ancestor:
     * {@code IC = -log2(numHierarchicalDescendants / numberOfClasses)}.
     * Threshold of 6.0 keeps ancestors covering at most ~1.5% of the ontology.
     *
     * <p>Calibrated from real MONDO examples (size ≈ 59k classes):
     * <ul>
     *   <li>MONDO_0016264 autoimmune hepatitis (7 desc) → IC 13.0 — keep</li>
     *   <li>MONDO_0005154 liver disorder (255 desc) → IC 7.85 — keep</li>
     *   <li>MONDO_0005560 brain disorder (1426 desc) → IC 5.37 — reject (too broad for "Depression")</li>
     *   <li>MONDO_0003847 hereditary disease (11808 desc) → IC 2.32 — reject</li>
     *   <li>MONDO_0000001 disease (27438 desc) → IC 1.10 — reject (root)</li>
     * </ul>
     *
     * <p>An IC-based threshold scales naturally across ontologies of different
     * sizes (MONDO 59k vs HP 18k vs EFO ~8k), unlike a fixed descendants cap.
     */
    private static final double MIN_IC = 6.0;

    /** Fallback descendants cap when we can't fetch the ontology size (network blip / new ontology). */
    private static final int FALLBACK_MAX_DESCENDANTS = 1000;

    /** Hardcoded root-ish IRIs we never want to surface, even below the cap. */
    private static final Set<String> DENYLIST_SHORT_FORMS = Set.of(
        "BFO_0000001", "BFO_0000002", "BFO_0000016", "BFO_0000017", "BFO_0000020",
        "MONDO_0000001", "MONDO_0700096", "MONDO_7770006", "MONDO_7770007",
        "HP_0000001", "HP_0000118",
        "EFO_0000001"
    );

    private final OlsClientRepo olsRepo;

    public AncestorSurfacer(OlsClientRepo olsRepo) {
        this.olsRepo = olsRepo;
    }

    /**
     * If the top-K candidates share a useful non-root ancestor in the same
     * target ontology, append it to {@code results}. Returns the (possibly
     * augmented) list. Caller is responsible for re-sorting.
     */
    public List<MapResult> augment(List<MapResult> results, Filter filter) {
        if (results == null || results.size() < MIN_LEAVES) return results;

        // Select leaves by raw matcher confidence (not the composite rankingScore)
        // so a cohesive group of strong semantic siblings doesn't get diluted by
        // weak lexical-substring hits or root-ish terms that survive on cross-method
        // RRF support. Also drop denylisted IRIs from the leaf set up front — they
        // sabotage the common-ancestor intersection.
        List<MapResult> sorted = results.stream()
            .filter(r -> r.error == null && r.ontologyTermID != null)
            .filter(r -> r.mappingConfidence >= MIN_LEAF_CONFIDENCE)
            .filter(r -> !DENYLIST_SHORT_FORMS.contains(r.ontologyTermID.toUpperCase(Locale.ROOT)))
            .sorted(Comparator.<MapResult>comparingDouble(r -> r.mappingConfidence).reversed())
            .collect(Collectors.toList());
        if (sorted.size() < MIN_LEAVES) return results;
        List<MapResult> topK = sorted.subList(0, Math.min(TOP_K, sorted.size()));
        List<MapResult> leaves = restrictToDominantOntology(topK);
        if (leaves.size() < MIN_LEAVES) return results;

        String rawOntology = leaves.get(0).ontologyURI;
        if (rawOntology == null) return results;
        final String ontology = rawOntology.toLowerCase(Locale.ROOT);

        // Fetch v2 detail for each leaf (parallel, cached). Read directAncestor
        // sets and intersect.
        List<String> leafIris = leaves.stream()
            .map(r -> expandToIri(r.ontologyTermID))
            .filter(s -> s != null)
            .collect(Collectors.toList());
        Map<String, OlsTerm> leafTerms = olsRepo.fetchTermsWithAncestors(leafIris, ontology);
        if (leafTerms.size() < MIN_LEAVES) return results;

        Set<String> common = null;
        for (var t : leafTerms.values()) {
            if (t.directAncestor == null) continue;
            Set<String> ancestors = new HashSet<>(t.directAncestor);
            if (common == null) common = ancestors;
            else common.retainAll(ancestors);
            if (common.isEmpty()) return results;
        }
        if (common == null || common.isEmpty()) return results;

        // Same-ontology filter + strip obvious roots.
        Set<String> filtered = common.stream()
            .filter(iri -> sameOntology(iri, ontology))
            .filter(iri -> !DENYLIST_SHORT_FORMS.contains(shortFormOf(iri)))
            .collect(Collectors.toSet());
        if (filtered.isEmpty()) return results;

        // Resolve each surviving common ancestor so we can score by IC and pick
        // the most-specific one above the threshold.
        Map<String, OlsTerm> ancestorTerms = olsRepo.fetchTermsWithAncestors(filtered, ontology);
        if (ancestorTerms.isEmpty()) return results;

        int ontologySize = olsRepo.getOntologyClassCount(ontology);
        OlsTerm lca = pickLCA(ancestorTerms.values(), ontologySize);
        if (lca == null) return results;

        // If the LCA happens to be in the candidate set already (e.g. as a
        // weak fuzzy-lexical hit), promote that entry rather than appending
        // a duplicate. Otherwise inject a fresh candidate.
        String lcaShort = lca.short_form != null ? lca.short_form : shortFormOf(lca.iri);
        MapResult existing = results.stream()
            .filter(r -> r.ontologyTermID != null && r.ontologyTermID.equalsIgnoreCase(lcaShort))
            .findFirst().orElse(null);
        if (existing != null) {
            promoteToUmbrella(existing, lca, ontology, leaves);
        } else {
            results.add(buildInjectedResult(lca, ontology, leaves));
        }
        return results;
    }

    /**
     * Upgrade an existing low-confidence hit on the LCA term so that it
     * carries the umbrella-surfacing provenance and a ranking score that
     * reflects support from the children that justified it.
     */
    private void promoteToUmbrella(MapResult existing, OlsTerm lca, String ontology, List<MapResult> leaves) {
        double meanConf = leaves.stream().mapToDouble(r -> r.mappingConfidence).average().orElse(0.0);
        double meanRank = leaves.stream().mapToDouble(r -> r.rankingScore).average().orElse(0.0);
        existing.mappingConfidence = Math.max(existing.mappingConfidence, meanConf);
        existing.rankingScore = Math.max(existing.rankingScore, meanRank);
        if (existing.ontologyTermLabel == null) existing.ontologyTermLabel = lca.label;
        List<V3MappingProvenanceStepDto> prov = existing.mappingProvenance == null
            ? new ArrayList<>()
            : new ArrayList<>(existing.mappingProvenance);
        MapResult anchor = leaves.get(0);
        prov.add(V3MappingProvenanceStepDto.superclass(
            expandToIri(anchor.ontologyTermID),
            anchor.ontologyTermLabel,
            lca.iri,
            lca.label,
            ontology
        ));
        existing.mappingProvenance = prov;
    }

    // -------- helpers --------

    /**
     * Pick the most specific ancestor whose Information Content is above the
     * threshold. IC = -log2(numHierarchicalDescendants / ontologyClassCount),
     * so highest IC == smallest descendant set == most specific.
     * Falls back to an absolute descendants cap when the ontology size lookup
     * fails (e.g. unfamiliar ontology, network issue).
     */
    private OlsTerm pickLCA(java.util.Collection<OlsTerm> candidates, int ontologySize) {
        boolean haveSize = ontologySize > 0;
        OlsTerm best = null;
        double bestIc = Double.NEGATIVE_INFINITY;
        for (var t : candidates) {
            if (t.numHierarchicalDescendants == null) continue;
            int desc = t.numHierarchicalDescendants;
            if (desc < 0) continue;
            double ic;
            if (haveSize) {
                // desc==0 (true leaf) → infinite IC; clamp to large finite via desc+1
                ic = -Math.log((desc + 1) / (double) ontologySize) / Math.log(2);
                if (ic < MIN_IC) continue;
            } else {
                if (desc > FALLBACK_MAX_DESCENDANTS) continue;
                ic = -desc; // smaller-desc-wins ordering when size unknown
            }
            if (ic > bestIc) {
                bestIc = ic;
                best = t;
            }
        }
        return best;
    }

    private List<MapResult> restrictToDominantOntology(List<MapResult> topK) {
        Map<String, List<MapResult>> byOnt = new HashMap<>();
        for (var r : topK) {
            String o = r.ontologyURI != null ? r.ontologyURI.toLowerCase(Locale.ROOT) : null;
            if (o == null) continue;
            byOnt.computeIfAbsent(o, k -> new ArrayList<>()).add(r);
        }
        return byOnt.values().stream()
            .max(Comparator.comparingInt(List::size))
            .orElse(List.of());
    }

    private MapResult buildInjectedResult(OlsTerm lca, String ontology, List<MapResult> leaves) {
        double meanConf = leaves.stream().mapToDouble(r -> r.mappingConfidence).average().orElse(0.0);
        double meanRank = leaves.stream().mapToDouble(r -> r.rankingScore).average().orElse(0.0);

        MapResult r = new MapResult();
        r.textToMap = leaves.get(0).textToMap;
        r.propertyType = leaves.get(0).propertyType;
        // Existing MapResults use lower-case short forms (e.g. "mondo_0005044"), so
        // mirror that here for consistent downstream display and de-dup matching.
        String shortForm = lca.short_form != null ? lca.short_form : shortFormOf(lca.iri);
        r.ontologyTermID = shortForm != null ? shortForm.toLowerCase(Locale.ROOT) : null;
        r.ontologyTermLabel = lca.label;
        r.ontologyTermSynonyms = lca.synonyms != null ? String.join("|", lca.synonyms) : null;
        r.ontologyURI = ontology;
        r.datasource = ontology;
        r.mappingConfidence = meanConf;
        r.rankingScore = meanRank;

        // Provenance: anchor the step to the highest-scoring leaf so the trail
        // shows which children justified surfacing this ancestor.
        MapResult anchor = leaves.get(0);
        r.mappingProvenance = List.of(V3MappingProvenanceStepDto.superclass(
            expandToIri(anchor.ontologyTermID),
            anchor.ontologyTermLabel,
            lca.iri,
            lca.label,
            ontology
        ));
        return r;
    }

    private static boolean sameOntology(String iri, String ontology) {
        if (iri == null) return false;
        String s = iri.toUpperCase(Locale.ROOT);
        String o = ontology.toUpperCase(Locale.ROOT);
        // OBO IRIs: ".../{ONT}_{id}"
        return s.contains("/" + o + "_");
    }

    private static String shortFormOf(String iri) {
        if (iri == null) return null;
        int slash = iri.lastIndexOf('/');
        int hash = iri.lastIndexOf('#');
        int start = Math.max(slash, hash) + 1;
        return iri.substring(start);
    }

    private static String expandToIri(String shortForm) {
        if (shortForm == null) return null;
        if (shortForm.startsWith("http")) return shortForm;
        // Most ontologies in OLS sit under purl.obolibrary.org/obo
        return "http://purl.obolibrary.org/obo/" + shortForm.toUpperCase(Locale.ROOT);
    }
}
