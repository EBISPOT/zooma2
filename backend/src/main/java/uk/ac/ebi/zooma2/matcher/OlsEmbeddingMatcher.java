package uk.ac.ebi.zooma2.matcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Finds embedding-based matches from OLS using embeddings.
 * Uses vector similarity search to find semantically related ontology terms.
 * 
 * Note: OLS only supports its own llama-based models for embedding search,
 * so this matcher always uses the OLS default model regardless of the
 * user-selected model (which may be an OpenAI model for local vector search).
 */
public class OlsEmbeddingMatcher implements AnnotationMatcher {

    private final OlsClientRepo olsRepo;
    private final double minSimilarity;
    private final int maxResults;
    private final int shallowResults;
    private final int timeoutMs;
    private static final String OLS_MODEL = "llama-embed-nemotron-8b_pca512";

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo) {
        this(olsRepo, 0.7, 100, 10, 60000);
    }

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo, double minSimilarity, int maxResults, int timeoutMs) {
        this(olsRepo, minSimilarity, maxResults, 10, timeoutMs);
    }

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo, double minSimilarity, int maxResults, int shallowResults, int timeoutMs) {
        this.olsRepo = olsRepo;
        this.minSimilarity = minSimilarity;
        this.maxResults = maxResults;
        this.shallowResults = shallowResults;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String getName() {
        return "ols_embedding";
    }

    @Override
    public List<Annotation> findMatches(MatchContext context) {
        return searchWithSize(context, shallowResults);
    }

    /**
     * Deep embedding search with full maxResults. Only call this if the shallow
     * search didn't return results from the target ontologies.
     */
    public List<Annotation> findDeepMatches(MatchContext context) {
        return searchWithSize(context, maxResults);
    }

    private List<Annotation> searchWithSize(MatchContext context, int size) {
        List<Annotation> annotations = new ArrayList<>();

        var terms = searchBothCasings(context.stringToMap, size);
        for (var term : terms) {
            if (term.score != null && term.score < minSimilarity) {
                continue;
            }
            annotations.add(createAnnotation(term, context));
        }

        return annotations;
    }

    /**
     * Embedding vectors are case-sensitive: "Cisplatin" and "cisplatin" land in
     * different neighbourhoods, and ontology labels are typically lowercase, so
     * a capitalised query can miss every relevant term. Search the query as
     * given and, when it contains upper case, its lowercased form too, merging
     * by IRI and keeping the higher score — case then never gates a match,
     * while queries whose casing is meaningful (gene symbols, acronyms) keep
     * their original-case results. Lowercase queries behave exactly as before.
     */
    private Collection<OlsTerm> searchBothCasings(String query, int size) {
        var results = olsRepo.findByEmbeddingSearch(query, OLS_MODEL, null, size, timeoutMs);
        String lowercased = query.toLowerCase(Locale.ROOT);
        if (lowercased.equals(query)) {
            return results;
        }
        var lowercasedResults = olsRepo.findByEmbeddingSearch(lowercased, OLS_MODEL, null, size, timeoutMs);
        Map<String, OlsTerm> byIri = new LinkedHashMap<>();
        for (var term : results) {
            if (term.iri != null) byIri.put(term.iri, term);
        }
        for (var term : lowercasedResults) {
            if (term.iri == null) continue;
            OlsTerm existing = byIri.get(term.iri);
            if (existing == null || score(term) > score(existing)) {
                byIri.put(term.iri, term);
            }
        }
        return byIri.values();
    }

    private static double score(OlsTerm term) {
        return term.score != null ? term.score : 0.0;
    }

    private Annotation createAnnotation(OlsTerm term, MatchContext context) {
        Annotation a = new Annotation();
        
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyType = context.propertyType != null ? context.propertyType : "unspecified";
        a.annotatedProperty.propertyValue = context.stringToMap;
        
        a.semanticTags = List.of(term.iri);
        a.resolvedTerm = term;
        a.confidence = capEmbeddingScore(term.score != null ? term.score : 0.6);
        
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
            OLS_MODEL,
            context.stringToMap,
            term.label,
            term.iri,
            term.score,
            null,
            "OLS_EMBEDDING"
        ));
        
        return a;
    }

    private static double capEmbeddingScore(double score) {
        return EvidenceTier.EMBEDDING.confidence(score);
    }
}
