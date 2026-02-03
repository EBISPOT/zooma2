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

    /** List of preferred ontology IDs (optional) */
    public final List<String> preferredOntologies;

    /** Results from a previous matcher (for chained matchers like superclass traversal) */
    public List<Annotation> previousResults;

    public MatchContext(String stringToMap, String propertyType, Filter sources, String model, List<String> preferredOntologies) {
        this.stringToMap = stringToMap;
        this.propertyType = propertyType;
        this.sources = sources;
        this.model = model;
        this.preferredOntologies = preferredOntologies;
        this.previousResults = null;
    }

    /**
     * Create a new context with previous results for chained matching.
     */
    public MatchContext withPreviousResults(List<Annotation> previousResults) {
        MatchContext newContext = new MatchContext(stringToMap, propertyType, sources, model, preferredOntologies);
        newContext.previousResults = previousResults;
        return newContext;
    }
}
