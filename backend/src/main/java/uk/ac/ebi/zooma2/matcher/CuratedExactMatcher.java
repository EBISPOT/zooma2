package uk.ac.ebi.zooma2.matcher;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.repo.MappingTableEntry;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;

/**
 * Finds exact matches from curated mapping tables.
 * These are high-confidence mappings that have been manually curated.
 */
public class CuratedExactMatcher implements AnnotationMatcher {

    private final MappingTablesRepo mappingTablesRepo;

    public CuratedExactMatcher(MappingTablesRepo mappingTablesRepo) {
        this.mappingTablesRepo = mappingTablesRepo;
    }

    @Override
    public String getName() {
        return "curated_exact";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        if (context.sources != null && isNone(context.sources.required)) {
            return List.of();
        }

        return mappingTablesRepo.exactMappingsForString(context.stringToMap)
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
        a.confidence = 1.0;

        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "DATABASE";
        a.provenance.source.name = m.databaseId;
        a.provenance.source.uri = m.databaseUrl;
        a.provenance.evidence = "CURATED_EXACT_MATCH";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();
        a.provenance.annotator = m.annotator;
        a.provenance.annotationDate = m.annotationDate;

        a.study = m.study;
        a.bioentity = m.bioentity;

        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.curated(
            m.databaseId,
            context.stringToMap,
            m.semanticTag,
            0.95
        ));

        return a;
    }

    private boolean isNone(List<String> list) {
        return list != null &&
            list.size() == 1 &&
            (list.get(0).equals("none") || list.get(0).equals("Select None"));
    }
}
