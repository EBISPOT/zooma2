package uk.ac.ebi.zooma2.search;

import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.matcher.MatchContext;
import uk.ac.ebi.zooma2.matcher.OlsEmbeddingMatcher;
import uk.ac.ebi.zooma2.matcher.OlsEmbeddingSimilarMatcher;
import uk.ac.ebi.zooma2.matcher.OlsLexicalMatcher;
import uk.ac.ebi.zooma2.matcher.OxoMatcher;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OxoClient;
import uk.ac.ebi.zooma2.util.RequestCancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrates the multi-phase ontology annotation search for a single string.
 *
 * <p>Phase 1 (always): OLS lexical + shallow OLS embedding run concurrently.
 * Phase 2 (conditional): deep embedding, OXO cross-mapping, and embedding-similarity
 * expansion run when the shallow pass either missed target ontologies (auto-escalation)
 * or was explicitly requested via {@code deep=true}.
 *
 * <p>The {@link #shallowPassOnly} ThreadLocal is set by {@code BatchMapper}'s first pass
 * to suppress auto-escalation — all shallow searches complete before any deep search starts.
 */
public class AnnotationEngine {

    /**
     * When set to {@code true} on the current thread, auto-escalation to deep search is
     * suppressed inside {@link #annotate}. Used by the two-pass batch mapping flow.
     */
    static final ThreadLocal<Boolean> shallowPassOnly = ThreadLocal.withInitial(() -> false);

    /** Sets the shallow-pass-only flag on the current thread. */
    public static void setShallowPassOnly(boolean value) {
        if (value) shallowPassOnly.set(true);
        else shallowPassOnly.remove();
    }

    private final OlsLexicalMatcher olsLexicalMatcher;
    private final OlsEmbeddingMatcher olsEmbeddingMatcher;
    private final OxoMatcher oxoMatcher;
    private final OlsEmbeddingSimilarMatcher olsEmbeddingSimilarMatcher;

    public AnnotationEngine(OlsClientRepo olsRepo) {
        OxoClient oxoClient = new OxoClient();

        var olsLexicalCfg = ZoomaConfig.config.ols_lexical;
        int lexicalMaxResults = (olsLexicalCfg != null && olsLexicalCfg.max_results != null) ? olsLexicalCfg.max_results : 20;
        int lexicalTimeoutMs = (olsLexicalCfg != null && olsLexicalCfg.timeout_ms != null) ? olsLexicalCfg.timeout_ms : 60000;
        this.olsLexicalMatcher = new OlsLexicalMatcher(olsRepo, lexicalMaxResults, lexicalTimeoutMs);

        var olsEmbeddingCfg = ZoomaConfig.config.ols_embedding;
        if (olsEmbeddingCfg != null) {
            double minSim = olsEmbeddingCfg.min_similarity != null ? olsEmbeddingCfg.min_similarity : 0.7;
            int maxRes = olsEmbeddingCfg.max_deep_results != null ? olsEmbeddingCfg.max_deep_results : 100;
            int shallowRes = olsEmbeddingCfg.max_shallow_results != null ? olsEmbeddingCfg.max_shallow_results : 10;
            int timeoutMs = olsEmbeddingCfg.timeout_ms != null ? olsEmbeddingCfg.timeout_ms : 60000;
            this.olsEmbeddingMatcher = new OlsEmbeddingMatcher(olsRepo, minSim, maxRes, shallowRes, timeoutMs);
        } else {
            this.olsEmbeddingMatcher = new OlsEmbeddingMatcher(olsRepo);
        }

        this.oxoMatcher = new OxoMatcher(oxoClient, olsRepo);
        this.olsEmbeddingSimilarMatcher = new OlsEmbeddingSimilarMatcher(olsRepo, null, olsRepo.getSimilarSemaphore());
    }

    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources) {
        return annotate(stringToMap, type, sources, "text-embedding-3-small", false);
    }

    /**
     * Runs all configured search strategies and returns the combined annotation stream.
     *
     * @param stringToMap the text to annotate
     * @param type        property type hint (may be {@code null})
     * @param sources     filter specifying target ontologies / datasources
     * @param model       embedding model identifier
     * @param deep        if {@code true}, always runs Phase 2 strategies regardless of Phase 1 results
     */
    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources, String model, boolean deep) {

        // Capture the cancellation flag from the calling (property-level) virtual thread
        // so it can be forwarded into the matcher-level virtual threads spawned below.
        // ThreadLocal is NOT inherited across virtual thread boundaries.
        final java.util.concurrent.atomic.AtomicBoolean cancelFlag = RequestCancellation.getFlag();

        MatchContext context = new MatchContext(stringToMap, type, sources, model);
        boolean hasTargets = context.targetOntologies != null && !context.targetOntologies.isEmpty();
        Set<String> targetsLower = hasTargets
            ? context.targetOntologies.stream().map(String::toLowerCase).collect(Collectors.toSet())
            : Set.of();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // Phase 1: OLS lexical + shallow OLS embedding run concurrently
            var olsLexicalFuture = executor.submit(() -> {
                if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                return olsLexicalMatcher.findMatches(context);
            });
            var olsEmbeddingFuture = executor.submit(() -> {
                if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                return olsEmbeddingMatcher.findMatches(context);
            });

            List<Annotation> allResults = new ArrayList<>();
            try {
                allResults.addAll(olsLexicalFuture.get());
                allResults.addAll(olsEmbeddingFuture.get());
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                return Stream.empty();
            }

            System.err.println("Phase 1 results for '" + stringToMap + "': " + allResults.size() +
                " (" + olsLexicalMatcher.getName() + "=" + olsLexicalFuture.get().size() +
                ", " + olsEmbeddingMatcher.getName() + "=" + olsEmbeddingFuture.get().size() + ")");

            // Phase 2 trigger: explicit deep flag, or shallow miss on target ontologies
            // (suppressed when shallowPassOnly is set — BatchMapper handles escalation itself)
            boolean needsDeep = deep;
            if (!deep && !shallowPassOnly.get() && hasTargets && !allResults.isEmpty()) {
                boolean hasTargetResult = allResults.stream().anyMatch(a ->
                    a.provenance != null && a.provenance.source != null && a.provenance.source.name != null
                    && targetsLower.contains(a.provenance.source.name.toLowerCase()));
                if (!hasTargetResult) {
                    System.err.println("Shallow search found no results from target ontologies " +
                        context.targetOntologies + " — escalating to deep");
                    needsDeep = true;
                }
            }

            if (needsDeep && !allResults.isEmpty()) {
                System.err.println("Running deep search for '" + stringToMap + "'");
                var deepFuture = executor.submit(() -> {
                    if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                    return olsEmbeddingMatcher.findDeepMatches(context);
                });
                List<Annotation> deepResults;
                try {
                    deepResults = deepFuture.get();
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    return allResults.stream();
                }
                if (!deepResults.isEmpty()) {
                    System.err.println("Deep embedding search found " + deepResults.size() + " additional results");
                    allResults.addAll(deepResults);
                }

                MatchContext expansionContext = context.withPreviousResults(allResults);

                var oxoFuture = executor.submit(() -> {
                    if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                    return oxoMatcher.findMatches(expansionContext);
                });
                var similarFuture = executor.submit(() -> {
                    if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                    return olsEmbeddingSimilarMatcher.findMatches(expansionContext);
                });

                List<Annotation> oxoResults;
                List<Annotation> embeddingSimilarResults;
                try {
                    oxoResults = oxoFuture.get();
                    embeddingSimilarResults = similarFuture.get();
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    return allResults.stream();
                }

                if (!oxoResults.isEmpty()) {
                    System.err.println("OXO expanded " + oxoResults.size() + " additional results to preferred ontologies");
                    allResults.addAll(oxoResults);
                }
                if (!embeddingSimilarResults.isEmpty()) {
                    System.err.println("OLS embedding similarity expanded " + embeddingSimilarResults.size() + " additional results");
                    allResults.addAll(embeddingSimilarResults);
                }
            }

            return allResults.stream();

        } catch (Exception e) {
            System.err.println("Error in parallel search: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Search failed for '" + stringToMap + "': " + e.getMessage(), e);
        }
    }
}
