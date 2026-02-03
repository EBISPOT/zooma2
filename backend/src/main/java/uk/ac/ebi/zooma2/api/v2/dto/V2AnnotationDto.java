package uk.ac.ebi.zooma2.api.v2.dto;

import java.util.List;
import uk.ac.ebi.zooma2.model.Annotation;

public class V2AnnotationDto {
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

    /**
     * Create a DTO from the internal Annotation model.
     */
    public static V2AnnotationDto from(Annotation internal) {
        V2AnnotationDto dto = new V2AnnotationDto();
        dto.semanticTags = internal.semanticTags;
        dto.confidence = internal.confidence;
        
        if (internal.annotatedProperty != null) {
            dto.annotatedProperty = new AnnotatedProperty();
            dto.annotatedProperty.uri = internal.annotatedProperty.uri;
            dto.annotatedProperty.propertyType = internal.annotatedProperty.propertyType;
            dto.annotatedProperty.propertyValue = internal.annotatedProperty.propertyValue;
        }
        
        if (internal.provenance != null) {
            dto.provenance = new Provenance();
            dto.provenance.evidence = internal.provenance.evidence;
            dto.provenance.accuracy = internal.provenance.accuracy;
            dto.provenance.generator = internal.provenance.generator;
            dto.provenance.generatedDate = internal.provenance.generatedDate;
            dto.provenance.annotator = internal.provenance.annotator;
            dto.provenance.annotationDate = internal.provenance.annotationDate;
            
            if (internal.provenance.source != null) {
                dto.provenance.source = new Source();
                dto.provenance.source.type = internal.provenance.source.type;
                dto.provenance.source.name = internal.provenance.source.name;
                dto.provenance.source.uri = internal.provenance.source.uri;
            }
        }
        
        return dto;
    }
}
