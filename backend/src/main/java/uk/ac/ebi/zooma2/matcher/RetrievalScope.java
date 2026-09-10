package uk.ac.ebi.zooma2.matcher;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How a retrieval call should be scoped to the request's target ontologies.
 *
 * <p>Retrieving the global top-k and filtering afterwards puts a ceiling on
 * recall for filtered searches: a target-ontology term outside the global
 * top-k can never be returned. So with targets set the matchers also query
 * within the targets. Under a hard filter ({@code includeOtherOntologies=false})
 * the scoped query replaces the global one; under a soft preference it runs
 * in addition and results are merged by IRI, keeping the higher score.
 */
public final class RetrievalScope {

    private RetrievalScope() {
    }

    public static boolean hasTargets(MatchContext context) {
        return context.targetOntologies != null && !context.targetOntologies.isEmpty();
    }

    /** True when only target-ontology results can be returned, so a global query is wasted. */
    public static boolean hardFilter(MatchContext context) {
        return hasTargets(context) && context.sources != null && !context.sources.includeOtherOntologies;
    }

    public static List<String> targets(MatchContext context) {
        if (!hasTargets(context)) return List.of();
        return context.targetOntologies.stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().collect(Collectors.toList());
    }

    /**
     * The targets to query one by one for an endpoint that accepts a single
     * ontology per call: all of them up to {@code maxScoped}, otherwise none (a
     * preset can carry a hundred ontologies, and a hundred embedding calls per
     * property is not a recall strategy). An empty result means "global only".
     */
    public static List<String> perOntologyTargets(MatchContext context, int maxScoped) {
        List<String> targets = targets(context);
        if (targets.isEmpty() || targets.size() > maxScoped) return List.of();
        return targets;
    }
}
