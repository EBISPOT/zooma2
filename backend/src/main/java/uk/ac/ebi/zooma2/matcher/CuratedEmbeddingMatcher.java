package uk.ac.ebi.zooma2.matcher;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.repo.MappingTableEntry;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;

/**
 * Finds embedding-based matches from the local vector index over curated mappings.
 * Uses embeddings to find similar property values in the curated mapping tables.
 */
public class CuratedEmbeddingMatcher implements AnnotationMatcher {

    private final MappingTablesRepo mappingTablesRepo;

    public CuratedEmbeddingMatcher(MappingTablesRepo mappingTablesRepo) {
        this.mappingTablesRepo = mappingTablesRepo;
    }

    @Override
    public String getName() {
        return "curated_embedding";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        if (context.sources != null && isNone(context.sources.required)) {
            return List.of();
        }

        return mappingTablesRepo.embeddingMappingsForString(context.stringToMap)
            .map(m -> createAnnotation(m, context))
            .collect(Collectors.toList());
    }

    private Annotation createAnnotation(MappingTableEntry m, MatchContext context) {
        Annotation a = new Annotation();

        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyType = m.propertyType != null ? m.propertyType : 
            (context.propertyType != null ? context.propertyType : "unspecified");
        a.annotatedProperty.propertyValue = m.propertyValue;

        a.semanticTags = List.of(m.semanticTag);
        a.confidence = capEmbeddingScore(m.similarityScore != null ? m.similarityScore : 0.75);

        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "DATABASE";
        a.provenance.source.name = m.databaseId;
        a.provenance.source.uri = m.databaseUrl;
        a.provenance.evidence = "CURATED_EMBEDDING_MATCH";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();
        a.provenance.annotator = m.annotator;
        a.provenance.annotationDate = m.annotationDate;

        a.study = m.study;
        a.bioentity = m.bioentity;

        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.semantic(
            m.databaseId,
            null, // model tracked elsewhere
            m.originalSearchTerm != null ? m.originalSearchTerm : context.stringToMap,
            m.propertyValue,
            m.semanticTag,
            m.similarityScore,
            null,
            "ZOOMA_EMBEDDING_OF_CURATED"
        ));

        return a;
    }

    private boolean isNone(List<String> list) {
        return list != null &&
            list.size() == 1 &&
            (list.get(0).equals("none") || list.get(0).equals("Select None"));
    }

    private static double capEmbeddingScore(double score) {
        return score * 0.89;
    }
}
