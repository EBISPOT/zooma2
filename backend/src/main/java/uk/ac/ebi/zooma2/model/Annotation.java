
package uk.ac.ebi.zooma2.model;

import java.util.List;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;

public class Annotation {
    public AnnotatedProperty annotatedProperty;
    public List<String> semanticTags;
    public double confidence; 
    public Provenance provenance;
    public List<V3MappingProvenanceStepDto> mappingProvenance;

    /** STUDY column from curated data (may be null for OLS-based matches). */
    public String study;
    /** BIOENTITY column from curated data (may be null for OLS-based matches). */
    public String bioentity;
    
    /** 
     * Reference to the source annotation that this annotation was derived from.
     * Used for provenance chain composition when matchers are chained.
     * This field is transient and not serialized.
     */
    public transient Annotation sourceAnnotation;

    public static class AnnotatedProperty {
        public String uri;
        public String propertyType;
        public String propertyValue;
    }

    public static class Provenance {
        public Source source;
        public String evidence;
        public String accuracy;
        public String generator;
        public String generatedDate;
        public String annotator;
        public String annotationDate;
    }

    public static class Source {
        public String type;
        public String name;
        public String uri;
    }
}
