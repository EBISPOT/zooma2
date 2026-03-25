package uk.ac.ebi.zooma2.matcher;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Finds fuzzy lexical matches from OLS using Solr's edismax search.
 * Complements exact tag_text matches with fuzzy/partial results.
 * Confidence capped at 0.85 to stay below exact matches.
 */
public class OlsLexicalMatcher implements AnnotationMatcher {

    private final OlsClientRepo olsRepo;
    private static final double MAX_CONFIDENCE = 0.85;

    public OlsLexicalMatcher(OlsClientRepo olsRepo) {
        this.olsRepo = olsRepo;
    }

    @Override
    public String getName() {
        return "ols_lexical";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        var terms = olsRepo.findByFuzzySearch(context.stringToMap, 20);
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

        // Compute confidence: exact label match gets MAX_CONFIDENCE, synonym slightly less, others lower
        boolean isExactLabel = t.label != null && t.label.equalsIgnoreCase(context.stringToMap);
        boolean isSynonymMatch = false;
        if (!isExactLabel && t.synonyms != null) {
            for (String syn : t.synonyms) {
                if (syn.equalsIgnoreCase(context.stringToMap)) {
                    isSynonymMatch = true;
                    break;
                }
            }
        }

        if (isExactLabel) {
            a.confidence = MAX_CONFIDENCE;
        } else if (isSynonymMatch) {
            a.confidence = MAX_CONFIDENCE - 0.05;
        } else {
            a.confidence = MAX_CONFIDENCE - 0.15;
        }

        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "ONTOLOGY";
        a.provenance.source.name = t.ontology_name;
        a.provenance.source.uri = t.ontology_name;
        a.provenance.evidence = "OLS_LEXICAL_FUZZY";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();

        String matchType = isExactLabel ? "OLS_LEXICAL_FUZZY_LABEL" 
            : isSynonymMatch ? "OLS_LEXICAL_FUZZY_SYNONYM"
            : "OLS_LEXICAL_FUZZY";

        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical(
            "ols:" + t.ontology_name,
            matchType,
            context.stringToMap,
            t.label,
            t.iri,
            a.confidence
        ));

        return a;
    }
}
