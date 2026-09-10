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
 *
 * <p>An exact label or synonym hit found here is the same evidence as the text
 * tagger's full match, so it is scored from the same {@link EvidenceTier} table
 * rather than with a matcher-specific number. Non-exact hits use a token-overlap
 * formula that stays below the partial-match cap.
 */
public class OlsLexicalMatcher implements AnnotationMatcher {

    private final OlsClientRepo olsRepo;
    private final int maxResults;
    private final int timeoutMs;
    /**
     * Weight applied to the token-Jaccard similarity of a non-exact fuzzy hit.
     * Kept as-is (issue #19 is reworking this formula); it sits below
     * {@link EvidenceTier#PARTIAL}'s cap so fuzzy hits never outrank substring hits.
     */
    private static final double FUZZY_PARTIAL_WEIGHT = 0.70;

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

        String matchType = isExactLabel ? "OLS_LEXICAL_FUZZY_LABEL"
            : isSynonymMatch ? "OLS_LEXICAL_FUZZY_SYNONYM"
            : "OLS_LEXICAL_FUZZY";

        EvidenceTier tier = EvidenceTier.ofMatchType(matchType);
        if (tier.isFullMatch()) {
            a.confidence = tier.confidence(1.0);
        } else {
            a.confidence = FUZZY_PARTIAL_WEIGHT * bestSimilarity(context.stringToMap, t);
        }

        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.type = "ONTOLOGY";
        a.provenance.source.name = t.ontology_name;
        a.provenance.source.uri = t.ontology_name;
        a.provenance.evidence = "OLS_LEXICAL_FUZZY";
        a.provenance.generator = "ZOOMA";
        a.provenance.generatedDate = new Date().toString();

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
