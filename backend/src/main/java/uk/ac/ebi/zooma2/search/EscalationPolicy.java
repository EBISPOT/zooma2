package uk.ac.ebi.zooma2.search;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.util.TermIds;
import uk.ac.ebi.zooma2.util.TermNamespace;

/**
 * The one place that decides whether a property needs the deep search phase.
 *
 * <p>Every entry point ({@code /map}, {@code /map-stream}, the legacy V2 path)
 * evaluates this over the same input: the shallow engine results together with
 * the text-tagger results, after conversion to {@link MapResult}s, so the
 * decision can never differ between endpoints or depend on which matcher
 * happened to report a term.
 *
 * <ul>
 *   <li>{@code deep=true}: always run Phase 2 (even when Phase 1 found nothing,
 *       which is when it is needed most).</li>
 *   <li>{@code deep=false}: never.</li>
 *   <li>{@code deep=null}: run it when target ontologies are set and nothing from
 *       a target ontology settles the search. A result settles it only if it is
 *       at least {@link #MIN_SATISFYING_CONFIDENCE} (a weaker result would not
 *       reliably survive weak-result suppression, leaving the caller with
 *       nothing from the ontology they asked for), belongs to a target ontology
 *       in the same sense the ontology filter uses (found in its file or defined
 *       in its namespace), is in the target's own namespace when
 *       {@code definingOnly} is set, and is not one the caller excluded.</li>
 * </ul>
 */
public final class EscalationPolicy {

    /**
     * Confidence a target-ontology result must reach to settle the search. Equal
     * to the Deduplicator's threshold for a best result being strong enough to
     * suppress others, so a settling result is one that survives to the response.
     */
    public static final double MIN_SATISFYING_CONFIDENCE = Deduplicator.WEAK_RESULT_MIN_BEST;

    private EscalationPolicy() {
    }

    public static boolean needsDeep(List<MapResult> results, Filter filter, Boolean deep, Set<String> excludedForms) {
        if (deep != null) return deep;
        return !isSatisfied(results, filter, excludedForms);
    }

    /** Annotation-level variant for callers that have no tagger results to combine (the engine's own composition). */
    public static boolean needsDeepForAnnotations(List<Annotation> annotations, Filter filter, Boolean deep) {
        if (deep != null) return deep;
        Set<String> targets = targetsOf(filter);
        if (targets.isEmpty()) return false;
        boolean definingOnly = filter != null && filter.definingOnly;
        return annotations.stream().noneMatch(a ->
            a.confidence >= MIN_SATISFYING_CONFIDENCE
            && settles(a.provenance != null && a.provenance.source != null ? a.provenance.source.name : null,
                       namespaceIdOf(a), targets, definingOnly));
    }

    static boolean isSatisfied(List<MapResult> results, Filter filter, Set<String> excludedForms) {
        Set<String> targets = targetsOf(filter);
        if (targets.isEmpty()) return true; // nothing to escalate for
        boolean definingOnly = filter != null && filter.definingOnly;
        return results.stream().anyMatch(r ->
            !r.isDiagnostic()
            && r.mappingConfidence >= MIN_SATISFYING_CONFIDENCE
            && !TermIds.matchesAny(excludedForms, r.ontologyTermID, r.ontologyTermIri)
            && settles(r.ontologyURI, r.ontologyTermID, targets, definingOnly));
    }

    private static boolean settles(String ontologyName, String namespaceId, Set<String> targets, boolean definingOnly) {
        if (TermNamespace.targetOntologyOf(ontologyName, namespaceId, targets) == null) return false;
        return !definingOnly || TermNamespace.inNamespaces(namespaceId, targets);
    }

    private static Set<String> targetsOf(Filter filter) {
        if (filter == null || filter.targetOntologies == null) return Set.of();
        return filter.targetOntologies.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static String namespaceIdOf(Annotation a) {
        if (a.resolvedTerm != null && a.resolvedTerm.short_form != null && !a.resolvedTerm.short_form.isBlank()) {
            return a.resolvedTerm.short_form;
        }
        return a.semanticTags != null && !a.semanticTags.isEmpty() ? a.semanticTags.get(0) : null;
    }
}
