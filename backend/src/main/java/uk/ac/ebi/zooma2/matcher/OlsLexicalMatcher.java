package uk.ac.ebi.zooma2.matcher;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
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
    private final int maxResults;
    private final int timeoutMs;
    private static final double MAX_CONFIDENCE = 0.85;

    public OlsLexicalMatcher(OlsClientRepo olsRepo) {
        this(olsRepo, 20, 60000);
    }

    public OlsLexicalMatcher(OlsClientRepo olsRepo, int maxResults, int timeoutMs) {
        this.olsRepo = olsRepo;
        this.maxResults = maxResults;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String getName() {
        return "ols_lexical";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        var terms = olsRepo.findByFuzzySearch(context.stringToMap, maxResults, timeoutMs);
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
        a.resolvedTerm = t;

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
            a.confidence = (MAX_CONFIDENCE - 0.15) * bestSimilarity(context.stringToMap, t);
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

    /** Best Jaccard token overlap between {@code query} and the term's label + all synonyms. */
    private double bestSimilarity(String query, OlsTerm t) {
        double best = t.label != null ? jaccardTokenOverlap(query, t.label) : 0.0;
        if (t.synonyms != null) {
            for (String syn : t.synonyms) {
                best = Math.max(best, jaccardTokenOverlap(query, syn));
            }
        }
        return best;
    }

    private double jaccardTokenOverlap(String a, String b) {
        Set<String> tokA = tokenSet(a);
        Set<String> tokB = tokenSet(b);
        if (tokA.isEmpty() && tokB.isEmpty()) return 1.0;
        if (tokA.isEmpty() || tokB.isEmpty()) return 0.0;
        long intersection = tokA.stream().filter(tokB::contains).count();
        long union = tokA.size() + tokB.size() - intersection;
        return (double) intersection / union;
    }

    private Set<String> tokenSet(String s) {
        return Arrays.stream(s.toLowerCase().split("\\W+"))
            .filter(t -> !t.isEmpty())
            .collect(Collectors.toSet());
    }
}
