package uk.ac.ebi.zooma2.model;

import java.util.List;

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
    
    public String getDescription() {
        return description != null && !description.isEmpty() ? description.get(0) : null;
    }
    
    public boolean isObsolete() {
        return is_obsolete != null && is_obsolete;
    }
}
