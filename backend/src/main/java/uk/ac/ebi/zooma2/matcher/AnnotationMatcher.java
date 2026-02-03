package uk.ac.ebi.zooma2.matcher;

import java.util.List;
import uk.ac.ebi.zooma2.model.Annotation;

/**
 * Interface for different annotation matching strategies.
 * Each implementation represents a different way to find ontology term mappings.
 */
public interface AnnotationMatcher {

    /**
     * Get the unique name of this matcher for logging/debugging.
     */
    String getName();

    /**
     * Find annotations for the given input string.
     * 
     * @param context The matching context containing all inputs and configuration
     * @return List of matching annotations
     */
    List<Annotation> findMatches(MatchContext context);
}
