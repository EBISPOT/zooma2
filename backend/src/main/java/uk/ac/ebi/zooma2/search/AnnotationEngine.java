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
import java.util.stream.Stream;

/**
 * Orchestrates the multi-phase ontology annotation search for a single string.
 *
 * <p>Phase 1 ({@link #annotateShallow}): OLS lexical + shallow OLS embedding run
 * concurrently. Phase 2 ({@link #annotateDeep}): deep embedding, OXO
 * cross-mapping (when enabled) and embedding-similarity expansion, seeded with
 * the Phase-1 annotations.
 *
 * <p>Whether Phase 2 runs is not decided here. {@code StringMapper} evaluates
 * {@link EscalationPolicy} over the converted Phase-1 results together with the
 * text-tagger results, so every entry point applies one criterion.
 * {@link #annotate(String, String, Filter, String, Boolean)} composes the two
 * phases for callers that have no tagger results.
 */
public class AnnotationEngine {

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
        return annotate(stringToMap, type, sources, "text-embedding-3-small", null);
    }

    /**
     * Runs Phase 1 and, per {@link EscalationPolicy}, Phase 2, and returns the
     * combined annotation stream. For callers without tagger results.
     *
     * @param deep {@code true} always runs Phase 2, {@code false} never, {@code null} auto-escalates
     */
    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources, String model, Boolean deep) {
        MatchContext context = new MatchContext(stringToMap, type, sources, model);
        List<Annotation> shallow = annotateShallow(context);
        if (Thread.currentThread().isInterrupted()) {
            return shallow.stream();
        }
        List<Annotation> all = new ArrayList<>(shallow);
        if (EscalationPolicy.needsDeepForAnnotations(shallow, sources, deep)) {
            all.addAll(annotateDeep(context, shallow));
        }
        return all.stream();
    }

    /** Phase 1: OLS lexical + shallow OLS embedding, concurrently. Empty (with the interrupt flag set) if interrupted. */
    public List<Annotation> annotateShallow(MatchContext context) {
        // Capture the cancellation flag from the calling (property-level) virtual thread
        // so it can be forwarded into the matcher-level virtual threads spawned below.
        // ThreadLocal is NOT inherited across virtual thread boundaries.
        final java.util.concurrent.atomic.AtomicBoolean cancelFlag = RequestCancellation.getFlag();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var olsLexicalFuture = executor.submit(() -> {
                if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                return olsLexicalMatcher.findMatches(context);
            });
            var olsEmbeddingFuture = executor.submit(() -> {
                if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                return olsEmbeddingMatcher.findMatches(context);
            });

            List<Annotation> results = new ArrayList<>();
            try {
                results.addAll(olsLexicalFuture.get());
                results.addAll(olsEmbeddingFuture.get());
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                return List.of();
            }

            System.err.println("Phase 1 results for '" + context.stringToMap + "': " + results.size() +
                " (" + olsLexicalMatcher.getName() + "=" + olsLexicalFuture.get().size() +
                ", " + olsEmbeddingMatcher.getName() + "=" + olsEmbeddingFuture.get().size() + ")");
            return results;

        } catch (Exception e) {
            System.err.println("Error in parallel search: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Search failed for '" + context.stringToMap + "': " + e.getMessage(), e);
        }
    }

    /**
     * Phase 2: deep embedding search, then OXO and embedding-similarity expansion
     * seeded with the Phase-1 annotations plus the deep hits. Returns only the
     * additional annotations; the caller already holds the Phase-1 ones.
     */
    public List<Annotation> annotateDeep(MatchContext context, List<Annotation> shallowResults) {
        final java.util.concurrent.atomic.AtomicBoolean cancelFlag = RequestCancellation.getFlag();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            System.err.println("Running deep search for '" + context.stringToMap + "'");
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
                return List.of();
            }
            List<Annotation> additional = new ArrayList<>(deepResults);
            if (!deepResults.isEmpty()) {
                System.err.println("Deep embedding search found " + deepResults.size() + " additional results");
            }

            List<Annotation> seeds = new ArrayList<>(shallowResults != null ? shallowResults : List.of());
            seeds.addAll(deepResults);
            MatchContext expansionContext = context.withPreviousResults(seeds);

            var oxoCfg = ZoomaConfig.config.oxo;
            boolean oxoEnabled = oxoCfg == null || oxoCfg.enabled == null || oxoCfg.enabled;

            var oxoFuture = oxoEnabled
                ? executor.submit(() -> {
                    if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                    return oxoMatcher.findMatches(expansionContext);
                })
                : null;
            var similarFuture = executor.submit(() -> {
                if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                return olsEmbeddingSimilarMatcher.findMatches(expansionContext);
            });

            List<Annotation> oxoResults;
            List<Annotation> embeddingSimilarResults;
            try {
                oxoResults = oxoFuture != null ? oxoFuture.get() : List.of();
                embeddingSimilarResults = similarFuture.get();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                return additional;
            }

            if (!oxoResults.isEmpty()) {
                System.err.println("OXO expanded " + oxoResults.size() + " additional results to preferred ontologies");
                additional.addAll(oxoResults);
            }
            if (!embeddingSimilarResults.isEmpty()) {
                System.err.println("OLS embedding similarity expanded " + embeddingSimilarResults.size() + " additional results");
                additional.addAll(embeddingSimilarResults);
            }
            return additional;

        } catch (Exception e) {
            System.err.println("Error in deep search: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Deep search failed for '" + context.stringToMap + "': " + e.getMessage(), e);
        }
    }
}
