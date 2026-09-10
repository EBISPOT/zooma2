package uk.ac.ebi.zooma2.matcher;

import java.util.List;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;

/**
 * Context object containing all inputs and configuration for annotation matching.
 */
public class MatchContext {

    /** The string to map to ontology terms */
    public final String stringToMap;

    /** The property type (optional) */
    public final String propertyType;

    /** Source filter (optional) */
    public final Filter sources;

    /** Embedding model name to use for semantic searches */
    public final String model;

    /** List of target ontology IDs (derived from sources filter) */
    public final List<String> targetOntologies;

    /** Results from a previous matcher (for chained matchers like superclass traversal) */
    public List<Annotation> previousResults;

    /** Wall-clock deadline (System.nanoTime based) for the current phase; 0 = no budget. */
    private volatile long deadlineNanos;
    private volatile long budgetMillis;

    public MatchContext(String stringToMap, String propertyType, Filter sources, String model) {
        this.stringToMap = stringToMap;
        this.propertyType = propertyType;
        this.sources = sources;
        this.model = model;
        this.targetOntologies = sources != null ? sources.targetOntologies : List.of();
        this.previousResults = null;
    }

    /**
     * Create a new context with previous results for chained matching. Shares this
     * context's time budget.
     */
    public MatchContext withPreviousResults(List<Annotation> previousResults) {
        MatchContext newContext = new MatchContext(stringToMap, propertyType, sources, model);
        newContext.previousResults = previousResults;
        newContext.deadlineNanos = deadlineNanos;
        newContext.budgetMillis = budgetMillis;
        return newContext;
    }

    /**
     * Starts (or restarts, for the next phase) a time budget of {@code millis}.
     * The stages a property goes through each carry their own 30–60 s timeouts;
     * chained, the worst case ran to minutes. The budget bounds the wall-clock
     * time a phase may spend: every OLS call is capped to what remains, and a
     * phase that starts with nothing left is skipped and reported as truncated.
     * {@code millis <= 0} disables the budget.
     */
    public void startBudget(long millis) {
        budgetMillis = millis;
        deadlineNanos = millis > 0 ? System.nanoTime() + millis * 1_000_000L : 0L;
    }

    public boolean hasBudget() {
        return deadlineNanos != 0L;
    }

    public long budgetMillis() {
        return budgetMillis;
    }

    /** Milliseconds left in the budget; {@code Long.MAX_VALUE} without a budget, never negative. */
    public long remainingMillis() {
        if (deadlineNanos == 0L) return Long.MAX_VALUE;
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    public boolean isExpired() {
        return deadlineNanos != 0L && remainingMillis() <= 0L;
    }

    /** {@code timeoutMs}, capped to what remains of the budget (at least 1 ms so an expired budget still fails fast). */
    public int timeoutWithin(int timeoutMs) {
        long remaining = remainingMillis();
        return remaining >= timeoutMs ? timeoutMs : (int) Math.max(1L, remaining);
    }
}
