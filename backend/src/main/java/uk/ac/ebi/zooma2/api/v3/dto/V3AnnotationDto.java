package uk.ac.ebi.zooma2.api.v3.dto;

import java.util.List;

public class V3AnnotationDto {
    public AnnotatedProperty annotatedProperty;
    public List<String> semanticTags;
    public String confidence; 
    public Provenance provenance;

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
