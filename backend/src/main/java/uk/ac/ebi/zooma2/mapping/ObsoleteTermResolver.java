package uk.ac.ebi.zooma2.mapping;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves obsolete ontology terms to their current, non-obsolete replacement.
 *
 * <p>Given the terms a mapping run produced, finds every obsolete one and follows
 * its {@code term_replaced_by} / {@code annotation.consider} pointer until it
 * reaches a live term. Handles the three ways this used to fail silently:
 * <ul>
 *   <li>OBO ontologies (GO, ...) publish the replacement as a bare short form
 *       ({@code GO_0022414}) rather than an IRI, so every pointer is expanded
 *       with {@link PrefixMap#shortFormToIri} before it is resolved or looked up.</li>
 *   <li>A replacement can itself have been obsoleted since, so the chain is
 *       followed hop by hop (breadth-first, one batched OLS call per hop) up to
 *       {@link #MAX_HOPS}, with cycle detection.</li>
 *   <li>Terms pre-resolved by tag_text / llm_search only carry {@code is_obsolete}
 *       and never the replacement pointer, so those are re-fetched in full first.</li>
 * </ul>
 * A term whose chain cannot be completed (no pointer, unresolvable pointer,
 * cycle, or cap reached) is absent from the result and should be dropped by
 * the caller, as before.
 */
public class ObsoleteTermResolver {

    /** Maximum number of replacement hops followed before a candidate is dropped. */
    static final int MAX_HOPS = 5;

    /** Outcome for one obsolete term: the live term to emit plus one provenance step per hop. */
    public static final class Resolution {
        public final OlsTerm finalTerm;
        public final List<V3MappingProvenanceStepDto> steps;

        Resolution(OlsTerm finalTerm, List<V3MappingProvenanceStepDto> steps) {
            this.finalTerm = finalTerm;
            this.steps = Collections.unmodifiableList(steps);
        }
    }

    private final OlsClientRepo olsRepo;
    private final PrefixMap prefixMap;

    public ObsoleteTermResolver(OlsClientRepo olsRepo, PrefixMap prefixMap) {
        this.olsRepo = olsRepo;
        this.prefixMap = prefixMap;
    }

    /**
     * Resolves every obsolete term in {@code allTerms}.
     *
     * @param allTerms candidate terms keyed by IRI (as built by StringMapper); not modified
     * @return resolutions keyed by the obsolete term's own IRI. Obsolete terms absent
     *         from the map could not be resolved and should be dropped.
     */
    public Map<String, Resolution> resolve(Map<String, OlsTerm> allTerms) {
        // origin IRI -> the term the chain currently sits on
        Map<String, OlsTerm> active = new HashMap<>();
        for (var term : allTerms.values()) {
            if (term != null && term.iri != null && term.isObsolete()) {
                active.put(term.iri, term);
            }
        }
        if (active.isEmpty()) {
            return Map.of();
        }

        // Pre-resolved terms (tagger / embedding search) never carry the replacement
        // pointer, so fetch the full OLS record for any obsolete term lacking one.
        Set<String> incomplete = new HashSet<>();
        for (var term : active.values()) {
            if (term.getReplacementIri() == null) {
                incomplete.add(term.iri);
            }
        }
        if (!incomplete.isEmpty()) {
            for (var entry : olsRepo.resolveTerms(incomplete).entrySet()) {
                if (active.containsKey(entry.getKey())) {
                    active.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, Resolution> results = new HashMap<>();
        Map<String, List<V3MappingProvenanceStepDto>> steps = new HashMap<>();
        Map<String, Set<String>> visited = new HashMap<>();
        for (var origin : active.keySet()) {
            steps.put(origin, new ArrayList<>());
            visited.put(origin, new HashSet<>(Set.of(origin)));
        }

        for (int hop = 0; ; hop++) {
            // Chains that have reached a live term are done. (A "hop 0" finish happens
            // when the full record of a pre-resolved term turns out not to be obsolete.)
            for (var it = active.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (!entry.getValue().isObsolete()) {
                    results.put(entry.getKey(), new Resolution(entry.getValue(), steps.get(entry.getKey())));
                    it.remove();
                }
            }
            if (active.isEmpty()) {
                break;
            }
            if (hop == MAX_HOPS) {
                for (var origin : active.keySet()) {
                    System.err.println("Dropping obsolete term " + origin
                        + ": replacement chain longer than " + MAX_HOPS + " hops");
                }
                break;
            }

            // Work out where each remaining chain goes next; drop dead ends and cycles.
            Map<String, String> nextIri = new HashMap<>();
            for (var it = active.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                String origin = entry.getKey();
                OlsTerm current = entry.getValue();
                String raw = current.getReplacementIri();
                if (raw == null) {
                    System.err.println("Dropping obsolete term " + origin + ": " + current.iri + " has no replacement");
                    it.remove();
                    continue;
                }
                String iri = prefixMap.shortFormToIri(raw);
                if (!visited.get(origin).add(iri)) {
                    System.err.println("Dropping obsolete term " + origin + ": replacement chain cycles at " + iri);
                    it.remove();
                    continue;
                }
                nextIri.put(origin, iri);
            }

            // One OLS call per hop level for all chains together.
            Map<String, OlsTerm> resolved = nextIri.isEmpty()
                ? Map.of()
                : olsRepo.resolveTerms(new HashSet<>(nextIri.values()));

            for (var entry : nextIri.entrySet()) {
                String origin = entry.getKey();
                OlsTerm current = active.get(origin);
                OlsTerm next = resolved.get(entry.getValue());
                if (next == null) {
                    System.err.println("Dropping obsolete term " + origin + ": replacement " + entry.getValue() + " not resolvable");
                    active.remove(origin);
                    continue;
                }
                steps.get(origin).add(V3MappingProvenanceStepDto.obsoleteReplacement(
                    current.iri, current.label,
                    next.iri, next.label,
                    current.ontology_name
                ));
                active.put(origin, next);
            }
        }

        return results;
    }
}
