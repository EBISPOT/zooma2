package uk.ac.ebi.zooma2.model;

import java.util.List;
import java.util.Map;

public class OlsTerm {
    public String iri;
    public String label;
    public String short_form;
    public String ontology_name;
    public List<String> synonyms;
    public List<String> description;
    public Double score; // Similarity score from semantic/vector search
    public Boolean is_obsolete;
    public String term_replaced_by;
    public Map<String, List<String>> annotation;
    
    public String getDescription() {
        return description != null && !description.isEmpty() ? description.get(0) : null;
    }
    
    public boolean isObsolete() {
        if (is_obsolete != null && is_obsolete) return true;
        // OBO convention: obsolete terms have a label prefixed with "obsolete"
        return label != null && label.toLowerCase().startsWith("obsolete");
    }

    /**
     * Get the replacement IRI for an obsolete term.
     * Checks term_replaced_by first, then falls back to annotation.consider
     * (used by MONDO and other OBO ontologies).
     */
    public String getReplacementIri() {
        if (term_replaced_by != null) {
            return term_replaced_by;
        }
        if (annotation != null) {
            List<String> consider = annotation.get("consider");
            if (consider != null && !consider.isEmpty()) {
                return consider.get(0);
            }
        }
        return null;
    }
}
