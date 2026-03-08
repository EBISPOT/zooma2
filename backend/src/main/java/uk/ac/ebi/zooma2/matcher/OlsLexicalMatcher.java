package uk.ac.ebi.zooma2.matcher;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Finds lexical matches from OLS (exact label or synonym matches).
 * High confidence matches based on string equality.
 */
public class OlsLexicalMatcher implements AnnotationMatcher {

    private final OlsClientRepo olsRepo;

    public OlsLexicalMatcher(OlsClientRepo olsRepo) {
        this.olsRepo = olsRepo;
    }

    @Override
    public String getName() {
        return "ols_lexical";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        // Search all of OLS (no ontology filter); the deduplicator filters to target ontologies
        var terms = olsRepo.findByLabelAndOntologies(context.stringToMap, null);
        return terms.stream()
            .map(t -> createAnnotation(t, context))
            .collect(Collectors.toList());
    }

    private Annotation createAnnotation(OlsTerm t, MatchContext context) {
        Annotation a = new Annotation();
        
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyType = context.propertyType != null ? context.propertyType : "unspecified";
        a.annotatedProperty.propertyValue = context.stringToMap;
        
        a.semanticTags = List.of(t.iri);
        a.confidence = t.label != null && t.label.equalsIgnoreCase(context.stringToMap) ? 1.0 : 0.9;

        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "ONTOLOGY";
        a.provenance.source.name = t.ontology_name;
        a.provenance.source.uri = t.ontology_name;
        a.provenance.evidence = "OLS_LEXICAL";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();

        String matchType = t.label != null && t.label.equalsIgnoreCase(context.stringToMap) 
            ? "OLS_LEXICAL" 
            : "OLS_LEXICAL_SYNONYM";

        double similarity = matchType.equals("OLS_LEXICAL") ? 1.0 : 0.9;

        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical(
            "ols:" + t.ontology_name,
            matchType,
            context.stringToMap,
            t.label,
            t.iri,
            similarity
        ));

        return a;
    }

    private boolean isNone(List<String> list) {
        return list != null &&
            list.size() == 1 &&
            (list.get(0).equals("none") || list.get(0).equals("Select None"));
    }
}
