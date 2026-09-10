package uk.ac.ebi.zooma2.api.v2.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.ac.ebi.zooma2.model.Annotation;

public class V2AnnotationDto {
    public String uri;
    public AnnotatedProperty annotatedProperty;
    public Links _links;
    public List<String> semanticTags;
    public List<Object> replacedBy;
    public List<Object> replaces;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public V2AnnotationDto derivedFrom;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String confidence; 
    public List<Object> annotatedBiologicalEntities;
    public Provenance provenance;

    public static class AnnotatedProperty {
        public String uri;
        public String propertyType;
        public String propertyValue;
    }

    public static class Links {
        public List<OlsLink> olslinks;
    }

    public static class OlsLink {
        public String href;
        public String semanticTag;
    }

    public static class Provenance {
        public Source source;
        public String evidence;
        public String accuracy;
        public String generator;
        public Long generatedDate;
        public String annotator;
        public Long annotationDate;
    }

    public static class Source {
        public String type;
        public String name;
        public String uri;
    }

    public static class BiologicalEntity {
        public String uri;
        public String name;
        public List<String> types;
        public List<Study> studies;
    }

    public static class Study {
        public String uri;
        public String accession;
        public List<String> types;
    }

    /**
     * Create a DTO from the internal Annotation model.
     */
    public static V2AnnotationDto from(Annotation internal) {
        long now = System.currentTimeMillis();

        // Build the derivedFrom (original annotation)
        V2AnnotationDto derived;
        if (internal.sourceAnnotation != null) {
            derived = buildRaw(internal.sourceAnnotation);
        } else {
            derived = buildRaw(internal);
        }

        // Build the outer ZOOMA-inferred wrapper
        V2AnnotationDto dto = new V2AnnotationDto();
        dto.uri = null;
        dto.semanticTags = internal.semanticTags;
        dto.confidence = V2ConfidenceLevel.of(internal.confidence, internal.mappingProvenance);
        dto.replacedBy = Collections.emptyList();
        dto.replaces = Collections.emptyList();
        dto.annotatedBiologicalEntities = Collections.emptyList();
        dto.derivedFrom = derived;

        if (internal.annotatedProperty != null) {
            dto.annotatedProperty = new AnnotatedProperty();
            dto.annotatedProperty.uri = internal.annotatedProperty.uri;
            dto.annotatedProperty.propertyType = internal.annotatedProperty.propertyType;
            dto.annotatedProperty.propertyValue = internal.annotatedProperty.propertyValue;
        }

        dto._links = buildLinks(internal.semanticTags);

        // Outer provenance is always ZOOMA-inferred
        dto.provenance = new Provenance();
        dto.provenance.source = new Source();
        dto.provenance.source.type = "DATABASE";
        dto.provenance.source.name = "zooma";
        dto.provenance.source.uri = "www.ebi.ac.uk/spot/zooma";
        dto.provenance.evidence = "ZOOMA_INFERRED_FROM_CURATED";
        dto.provenance.accuracy = null;
        dto.provenance.generator = "ZOOMA";
        dto.provenance.generatedDate = now;
        dto.provenance.annotator = "ZOOMA";
        dto.provenance.annotationDate = now;

        return dto;
    }

    /** Build a raw annotation DTO without the ZOOMA-inferred wrapper. Used for derivedFrom. */
    private static V2AnnotationDto buildRaw(Annotation internal) {
        V2AnnotationDto dto = new V2AnnotationDto();
        dto.uri = null;
        dto.semanticTags = internal.semanticTags;
        dto.replacedBy = Collections.emptyList();
        dto.replaces = Collections.emptyList();
        dto.annotatedBiologicalEntities = buildBiologicalEntities(internal);

        if (internal.annotatedProperty != null) {
            dto.annotatedProperty = new AnnotatedProperty();
            dto.annotatedProperty.uri = internal.annotatedProperty.uri;
            dto.annotatedProperty.propertyType = internal.annotatedProperty.propertyType;
            dto.annotatedProperty.propertyValue = internal.annotatedProperty.propertyValue;
        }

        dto._links = buildLinks(internal.semanticTags);

        if (internal.provenance != null) {
            dto.provenance = new Provenance();
            dto.provenance.evidence = internal.provenance.evidence;
            dto.provenance.accuracy = internal.provenance.accuracy;
            dto.provenance.generator = internal.provenance.generator;
            dto.provenance.generatedDate = parseDateToEpochMs(internal.provenance.generatedDate);
            dto.provenance.annotator = internal.provenance.annotator;
            dto.provenance.annotationDate = parseDateToEpochMs(internal.provenance.annotationDate);

            if (internal.provenance.source != null) {
                dto.provenance.source = new Source();
                dto.provenance.source.type = internal.provenance.source.type;
                dto.provenance.source.name = internal.provenance.source.name;
                dto.provenance.source.uri = internal.provenance.source.uri;
            }
        }

        return dto;
    }

    private static List<Object> buildBiologicalEntities(Annotation internal) {
        if (internal.bioentity == null || internal.bioentity.isBlank()) {
            return Collections.emptyList();
        }
        String sourceName = internal.provenance != null && internal.provenance.source != null
                ? internal.provenance.source.name : "unknown";

        BiologicalEntity be = new BiologicalEntity();
        be.uri = "http://rdf.ebi.ac.uk/resource/zooma/" + sourceName + "/" +
                md5hex(sourceName + ":" + internal.bioentity);
        be.name = internal.bioentity;
        be.types = List.of(
                "http://www.w3.org/2002/07/owl#NamedIndividual",
                "http://rdf.ebi.ac.uk/terms/zooma/Target");

        if (internal.study != null && !internal.study.isBlank()) {
            Study st = new Study();
            st.uri = "http://rdf.ebi.ac.uk/resource/zooma/" + sourceName + "/" +
                    md5hex(sourceName + ":" + internal.study);
            st.accession = internal.study;
            st.types = List.of(
                    "http://www.w3.org/2002/07/owl#NamedIndividual",
                    "http://rdf.ebi.ac.uk/terms/zooma/DatabaseEntrySource");
            be.studies = List.of(st);
        } else {
            be.studies = Collections.emptyList();
        }
        return List.of(be);
    }

    private static String md5hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Links buildLinks(List<String> semanticTags) {
        if (semanticTags == null || semanticTags.isEmpty()) {
            return null;
        }
        Links links = new Links();
        links.olslinks = new ArrayList<>();
        for (String tag : semanticTags) {
            OlsLink link = new OlsLink();
            link.semanticTag = tag;
            link.href = "https://www.ebi.ac.uk/ols4/api/terms?iri=" +
                URLEncoder.encode(tag, StandardCharsets.UTF_8);
            links.olslinks.add(link);
        }
        return links;
    }

    private static Long parseDateToEpochMs(String dateStr) {
        if (dateStr == null) return null;
        // Try parsing as epoch ms first (just a number)
        try {
            return Long.parseLong(dateStr);
        } catch (NumberFormatException ignored) {}
        // Try Date.toString() format: "Thu Mar 05 23:13:25 GMT 2026"
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);
            return sdf.parse(dateStr).getTime();
        } catch (ParseException ignored) {}
        // Return current time as fallback
        return System.currentTimeMillis();
    }
}
