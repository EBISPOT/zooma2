package uk.ac.ebi.zooma2.matcher;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.util.Diagnostics;
import uk.ac.ebi.zooma2.util.StringSimilarity;

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
     * Weight applied to the blended similarity of a non-exact fuzzy hit; it sits
     * below {@link EvidenceTier#PARTIAL}'s cap so fuzzy hits never outrank
     * substring hits.
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

    /**
     * The global search returns the global top-k, so a target-ontology term that
     * sits below dozens of hits from bigger ontologies is unreachable however
     * well it matches. With target ontologies the search is also run restricted
     * to them (one call: OLS OR-filters repeated ontologyId parameters); under a
     * hard filter only that call is made, under a soft preference both are and
     * the results are merged by IRI.
     */
    @Override
    public List<Annotation> findMatches(MatchContext context) {
        if (context.isExpired()) {
            Diagnostics.warn("Time budget exhausted before the lexical search; results may be incomplete");
            return List.of();
        }
        Map<String, OlsTerm> byIri = new LinkedHashMap<>();
        if (!RetrievalScope.hardFilter(context)) {
            merge(byIri, olsRepo.findByFuzzySearch(context.stringToMap, maxResults, context.timeoutWithin(timeoutMs)));
        }
        if (RetrievalScope.hasTargets(context)) {
            merge(byIri, olsRepo.findByFuzzySearch(context.stringToMap, maxResults, context.timeoutWithin(timeoutMs), RetrievalScope.targets(context)));
        }
        return byIri.values().stream()
            .map(t -> createAnnotation(t, context))
            .collect(Collectors.toList());
    }

    private static void merge(Map<String, OlsTerm> byIri, List<OlsTerm> terms) {
        for (OlsTerm t : terms) {
            if (t.iri == null) continue; // an entity without an IRI cannot be a mapping
            byIri.putIfAbsent(t.iri, t);
        }
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

    /**
     * Best similarity between the query and the term's label or any synonym:
     * token overlap blended with character-level similarity (see
     * {@link StringSimilarity#blended}), so a one-token typo or morphological
     * variant ("melanomma" / "melanoma") no longer scores zero. OLS's entity
     * search returns no relevance score of its own, so this is the only signal.
     */
    private double bestSimilarity(String query, OlsTerm t) {
        double best = t.label != null ? StringSimilarity.blended(query, t.label) : 0.0;
        if (t.synonyms != null) {
            for (String syn : t.synonyms) {
                best = Math.max(best, StringSimilarity.blended(query, syn));
            }
        }
        return best;
    }
}
