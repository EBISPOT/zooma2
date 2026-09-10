package uk.ac.ebi.zooma2.matcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.concurrent.Callable;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.util.ConcurrentCalls;
import uk.ac.ebi.zooma2.util.Diagnostics;

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
    private final int maxScopedOntologies;
    private static final String OLS_MODEL = "llama-embed-nemotron-8b_pca512";
    /** Default for the most target ontologies queried one by one (llm_search takes a single ontologyId per call). */
    public static final int DEFAULT_MAX_SCOPED_ONTOLOGIES = 5;

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo) {
        this(olsRepo, 0.7, 100, 10, 60000);
    }

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo, double minSimilarity, int maxResults, int timeoutMs) {
        this(olsRepo, minSimilarity, maxResults, 10, timeoutMs);
    }

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo, double minSimilarity, int maxResults, int shallowResults, int timeoutMs) {
        this(olsRepo, minSimilarity, maxResults, shallowResults, timeoutMs, DEFAULT_MAX_SCOPED_ONTOLOGIES);
    }

    public OlsEmbeddingMatcher(OlsClientRepo olsRepo, double minSimilarity, int maxResults, int shallowResults, int timeoutMs, int maxScopedOntologies) {
        this.olsRepo = olsRepo;
        this.minSimilarity = minSimilarity;
        this.maxResults = maxResults;
        this.shallowResults = shallowResults;
        this.timeoutMs = timeoutMs;
        this.maxScopedOntologies = maxScopedOntologies;
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
     * search didn't return results from the target ontologies. It cannot be
     * skipped on the strength of a short or low-scoring shallow page: OLS's
     * approximate nearest-neighbour search is not monotonic across page sizes
     * (in 7 of 54 cached page pairs a term outside the top ten outscored the
     * tenth), so only the engine's filtering of already-seen terms is safe.
     */
    public List<Annotation> findDeepMatches(MatchContext context) {
        return searchWithSize(context, maxResults);
    }

    private List<Annotation> searchWithSize(MatchContext context, int size) {
        List<Annotation> annotations = new ArrayList<>();

        var terms = search(context, size);
        for (var term : terms) {
            // A term without a similarity score cannot be shown to clear the
            // threshold, and one without an IRI cannot be a mapping at all.
            if (term.iri == null || term.score == null || term.score < minSimilarity) {
                continue;
            }
            annotations.add(createAnnotation(term, context));
        }

        return annotations;
    }

    /**
     * Runs the searches a query needs and merges them by IRI, keeping the higher score.
     *
     * <p>Casing: embedding vectors are case-sensitive: "Cisplatin" and "cisplatin"
     * land in different neighbourhoods, and ontology labels are typically
     * lowercase, so a capitalised query is also searched lowercased; queries whose
     * casing is meaningful (gene symbols, acronyms) keep their original-case
     * results.
     *
     * <p>Scope: the global top-k cannot reach a target-ontology term that sits
     * below dozens of hits from bigger ontologies, so with target ontologies the
     * query is also run restricted to each of them (llm_search takes one
     * ontologyId per call; verified that two return nothing), up to
     * {@code maxScopedOntologies}. Under a hard filter the scoped calls replace
     * the global one; under a soft preference they add to it.
     */
    private Collection<OlsTerm> search(MatchContext context, int size) {
        List<String> scoped = RetrievalScope.perOntologyTargets(context, maxScopedOntologies);
        boolean global = !RetrievalScope.hardFilter(context) || scoped.isEmpty();
        if (RetrievalScope.hasTargets(context) && scoped.isEmpty()) {
            System.err.println("Embedding search for '" + context.stringToMap + "': " + context.targetOntologies.size()
                + " target ontologies exceed max_scoped_ontologies=" + maxScopedOntologies + ", global search only");
        }
        if (context.isExpired()) {
            Diagnostics.warn("Time budget exhausted before the embedding search; results may be incomplete");
            return List.of();
        }

        // Every (casing, scope) query is independent, so they run concurrently: the
        // dual-casing search used to double the latency of any capitalised query.
        // Results are merged in a fixed order afterwards so ties resolve the same
        // way as a sequential run would.
        List<Callable<Collection<OlsTerm>>> calls = new ArrayList<>();
        for (String casing : casings(context.stringToMap)) {
            if (global) {
                calls.add(() -> olsRepo.findByEmbeddingSearch(casing, OLS_MODEL, null, size, context.timeoutWithin(timeoutMs)));
            }
            for (String ontologyId : scoped) {
                calls.add(() -> olsRepo.findByEmbeddingSearch(casing, OLS_MODEL, ontologyId, size, context.timeoutWithin(timeoutMs)));
            }
        }
        List<Collection<OlsTerm>> raw = ConcurrentCalls.run(calls);

        Map<String, OlsTerm> byIri = new LinkedHashMap<>();
        for (Collection<OlsTerm> terms : raw) {
            merge(byIri, terms);
        }
        return byIri.values();
    }

    private static List<String> casings(String query) {
        String lowercased = query.toLowerCase(Locale.ROOT);
        return lowercased.equals(query) ? List.of(query) : List.of(query, lowercased);
    }

    private static void merge(Map<String, OlsTerm> byIri, Collection<OlsTerm> terms) {
        for (var term : terms) {
            if (term.iri == null) continue;
            OlsTerm existing = byIri.get(term.iri);
            if (existing == null || score(term) > score(existing)) {
                byIri.put(term.iri, term);
            }
        }
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
        a.confidence = capEmbeddingScore(term.score);
        
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
