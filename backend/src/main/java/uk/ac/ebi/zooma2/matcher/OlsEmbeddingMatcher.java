package uk.ac.ebi.zooma2.matcher;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Finds embedding-based matches from OLS using embeddings.
 * Uses vector similarity search to find semantically related ontology terms.
 */
public class OlsEmbeddingMatcher implements AnnotationMatcher {

    private final OlsClientRepo olsRepo;
    private final double minSimilarity;
    private final int maxResults;

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo) {
        this(olsRepo, 0.7, 250);
    }

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo, double minSimilarity, int maxResults) {
        this.olsRepo = olsRepo;
        this.minSimilarity = minSimilarity;
        this.maxResults = maxResults;
    }

    @Override
    public String getName() {
        return "ols_embedding";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        List<Annotation> annotations = new ArrayList<>();
        
        var terms = olsRepo.findByEmbeddingSearch(context.stringToMap, context.model, null, maxResults);
        
        for (var term : terms) {
            // Filter by minimum similarity
            if (term.score != null && term.score < minSimilarity) {
                continue;
            }
            var annotation = createAnnotation(term, context);
            annotations.add(annotation);
        }
        
        return annotations;
    }

    private Annotation createAnnotation(OlsTerm term, MatchContext context) {
        Annotation a = new Annotation();
        
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyType = context.propertyType != null ? context.propertyType : "unspecified";
        a.annotatedProperty.propertyValue = context.stringToMap;
        
        a.semanticTags = List.of(term.iri);
        a.confidence = "MEDIUM";
        
        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "ONTOLOGY";
        a.provenance.source.name = term.ontology_name;
        a.provenance.source.uri = term.ontology_name;
        a.provenance.evidence = "OLS_EMBEDDING";
        a.provenance.accuracy = "NOT_SPECIFIED";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();

        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.semantic(
            "ols:" + term.ontology_name,
            context.model,
            context.stringToMap,
            term.label,
            term.iri,
            term.score,
            null,
            "OLS_EMBEDDING"
        ));
        
        return a;
    }
}
