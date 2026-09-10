package uk.ac.ebi.zooma2.repo;

import java.util.List;

public class OlsOntology {

    public String ontologyId;
    public OlsOntologyConfig config;

    public static class OlsOntologyConfig {
        public String title;
        public String description;
        /** OLS's prefix for this ontology's short forms (e.g. EFO, mesh, ORDO). */
        public String preferredPrefix;
        /** IRI prefixes OLS strips to form a short form (e.g. http://www.ebi.ac.uk/efo/EFO_). */
        public List<String> baseUris;
    }
    
}
