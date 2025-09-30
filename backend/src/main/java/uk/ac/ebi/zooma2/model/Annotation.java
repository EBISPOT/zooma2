
package uk.ac.ebi.zooma2.model;

import java.util.List;

public class Annotation {
    // public String uri;
    public AnnotatedProperty annotatedProperty;
    public List<String> semanticTags;
    // public List<String> replacedBy;
    // public List<String> replaces;
    // public DerivedFrom derivedFrom;

    public String confidence; 

    public Provenance provenance;

    // public List<AnnotatedBiologicalEntity> annotatedBiologicalEntities;

    public static class AnnotatedProperty {
        public String uri;
        public String propertyType;
        public String propertyValue;
    }

    // public static class DerivedFrom {
    //     public String uri;
    //     public AnnotatedProperty annotatedProperty;
    //     public List<String> semanticTags;
    //     public List<String> replacedBy;
    //     public List<String> replaces;
    //     public Provenance provenance;
    //     public List<AnnotatedBiologicalEntity> annotatedBiologicalEntities;
    // }

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

    // public static class AnnotatedBiologicalEntity {
    //     public String uri;
    //     public String name;
    //     public List<String> types;
    //     public List<Study> studies;
    // }

    // public static class Study {
    //     public String uri;
    //     public String accession;
    //     public List<String> types;
    // }
}
