package uk.ac.ebi.zooma2.repo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.util.CachedHttpClient;

import java.util.concurrent.Semaphore;

public class OlsClientRepo {

    private static final byte TERM_SEPARATOR_BYTE = (byte) '\n';

    private final Semaphore embeddingSemaphore;
    private final Semaphore similarSemaphore;
    private final Semaphore lexicalSemaphore;

    public OlsClientRepo(int maxConcurrentEmbedding, int maxConcurrentSimilar, int maxConcurrentLexical) {
        this.embeddingSemaphore = new Semaphore(maxConcurrentEmbedding);
        this.similarSemaphore = new Semaphore(maxConcurrentSimilar);
        this.lexicalSemaphore = new Semaphore(maxConcurrentLexical);
    }

    public OlsClientRepo() {
        this(3, 10, 5);
    }

    Gson gson = new Gson();

    private OlsTermCache termCache;

    public static String getOlsUrl() {
        return ZoomaConfig.getOlsUrl();
    }

    public void setTermCache(OlsTermCache cache) {
        this.termCache = cache;
    }

    public OlsTermCache getTermCache() {
        return termCache;
    }

    /** ontologyId -> ontology, built once from getOntologies(); null until loaded. */
    private volatile Map<String, OlsOntology> ontologyIndex;
    private volatile long ontologyIndexFailedAtMillis;
    private static final long ONTOLOGY_INDEX_RETRY_MILLIS = 5 * 60 * 1000L;

    /**
     * The OLS-style short form of a term IRI in the given ontology (see
     * {@link OlsShortForms}), using the ontology's configured prefix and base
     * URIs. If the ontology list cannot be loaded, falls back to the rule with no
     * configuration, which is exact for OBO PURLs and EFO-style IRIs.
     */
    public String olsShortForm(String ontologyId, String iri) {
        OlsOntology ontology = ontologyId != null ? ontologyConfig(ontologyId) : null;
        if (ontology == null || ontology.config == null) {
            return OlsShortForms.shortForm(ontologyId, null, null, iri);
        }
        return OlsShortForms.shortForm(ontologyId, ontology.config.preferredPrefix, ontology.config.baseUris, iri);
    }

    private OlsOntology ontologyConfig(String ontologyId) {
        Map<String, OlsOntology> index = ontologyIndex;
        if (index == null) {
            index = loadOntologyIndex();
        }
        return index.get(ontologyId.toLowerCase());
    }

    private synchronized Map<String, OlsOntology> loadOntologyIndex() {
        if (ontologyIndex != null) return ontologyIndex;
        // Don't hammer OLS during an outage: one attempt per retry window, and an
        // empty index (configuration-free short forms) in between.
        if (System.currentTimeMillis() - ontologyIndexFailedAtMillis < ONTOLOGY_INDEX_RETRY_MILLIS) {
            return Map.of();
        }
        try {
            Map<String, OlsOntology> index = new HashMap<>();
            for (OlsOntology o : getOntologies()) {
                if (o != null && o.ontologyId != null) index.put(o.ontologyId.toLowerCase(), o);
            }
            ontologyIndex = index;
            return index;
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not load the OLS ontology list for short forms: " + e.getMessage());
            ontologyIndexFailedAtMillis = System.currentTimeMillis();
            return Map.of();
        }
    }

    public List<OlsOntology> getOntologies() throws IOException {

        var json = urlToJson(getOlsUrl() + "/api/ontologies?size=1000");
        json = json.getAsJsonObject().get("_embedded").getAsJsonObject().get("ontologies");

        List<OlsOntology> ontologies = gson.fromJson(json, new TypeToken<List<OlsOntology>>(){}.getType());

        if(ontologies == null) {
            throw new RuntimeException("Failed to load ontologies from OLS");
        }

        return ontologies;
    }

    /**
     * Fetch ontology IDs from OLS v2 API whose IRI starts with http://purl.obolibrary.org/obo/ (OBO ontologies).
     */
    public List<String> getOboOntologyIds() throws IOException {
        var json = urlToJson(getOlsUrl() + "/api/v2/ontologies?size=1000");
        var elements = json.getAsJsonObject().getAsJsonArray("elements");
        List<String> ids = new ArrayList<>();
        for (var element : elements) {
            var obj = element.getAsJsonObject();
            var iri = obj.has("iri") && !obj.get("iri").isJsonNull() ? obj.get("iri").getAsString() : "";
            if (iri.startsWith("http://purl.obolibrary.org/obo/")) {
                ids.add(obj.get("ontologyId").getAsString());
            }
        }
        return ids;
    }

    /**
     * Whether a cached term carries everything resolveTerms is expected to return.
     *
     * <p>A full record from findByIdAndIsDefiningOntology always has is_obsolete set;
     * for an obsolete term it also has the replacement pointer (term_replaced_by, or
     * annotation.consider) or at least the annotation block. Terms derived from
     * search-endpoint entities carry only is_obsolete, so an obsolete one with no
     * replacement information and no annotation block is treated as partial and
     * re-fetched (the HTTP response is itself cached, so this is cheap).
     */
    static boolean isCompleteCacheEntry(OlsTerm t) {
        if (t.is_obsolete == null) return false;
        if (!t.isObsolete()) return true;
        return t.getReplacementIri() != null || t.annotation != null;
    }

    public Map<String, OlsTerm> resolveTerms(Collection<String> termIris) {

        if(termIris == null || termIris.isEmpty()) {
            return Map.of();
        }

        Map<String, OlsTerm> result = new HashMap<>();
        List<String> irisToFetch = new ArrayList<>();

        // Check cache first
        if (termCache != null) {
            Map<String, OlsTerm> cached = termCache.getTerms(termIris);
            
            for (String iri : termIris) {
                OlsTerm cachedTerm = cached.get(iri);
                if (cachedTerm == null || !isCompleteCacheEntry(cachedTerm)) {
                    irisToFetch.add(iri);
                } else {
                    result.put(iri, cachedTerm);
                }
            }
            
        } else {
            irisToFetch.addAll(termIris);
        }

        if (irisToFetch.isEmpty()) {
            return result;
        }

        // Track which IRIs we're fetching so we can mark failures
        Set<String> fetchedIris = new HashSet<>(irisToFetch);

        // Fetch remaining from OLS on virtual threads (blocking I/O; the common
        // ForkJoinPool a parallel stream would use is CPU-sized and JVM-shared).
        final java.util.concurrent.atomic.AtomicBoolean cancelFlag = uk.ac.ebi.zooma2.util.RequestCancellation.getFlag();
        List<OlsTerm> resolved = new ArrayList<>();
        List<String> transportFailures = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<TermFetch>> futures = irisToFetch.stream()
                .filter(iri -> iri != null && !iri.isEmpty())
                .map(iri -> executor.submit(() -> {
                    if (cancelFlag != null) uk.ac.ebi.zooma2.util.RequestCancellation.setFlag(cancelFlag);
                    return fetchTerm(iri);
                }))
                .toList();
            for (var future : futures) {
                try {
                    TermFetch fetch = future.get();
                    if (fetch.term != null) resolved.add(fetch.term);
                    else if (fetch.transportFailure) transportFailures.add(fetch.message);
                    else notFound.add(fetch.iri);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    transportFailures.add(String.valueOf(e.getCause()));
                }
            }
        }
        if (!transportFailures.isEmpty() && !uk.ac.ebi.zooma2.util.RequestCancellation.isCancelled()) {
            uk.ac.ebi.zooma2.util.Diagnostics.warn("OLS term lookup failed for " + transportFailures.size() + " of "
                + irisToFetch.size() + " terms (" + transportFailures.get(0) + "); candidates for those terms are missing");
        }

        // Save to cache and add to result
        if (termCache != null && !resolved.isEmpty()) {
            termCache.saveTerms(resolved);
        }

        // Track failed IRIs (those we tried to fetch but didn't resolve)
        Set<String> resolvedIris = new HashSet<>();
        for(var term : resolved) {
            result.put(term.iri, term);
            resolvedIris.add(term.iri);
        }
        
        if (termCache != null && !notFound.isEmpty()) {
            // Only a definitive "no such term" from OLS is recorded; a transport
            // failure must not become a permanent negative entry.
            termCache.markFailed(notFound);
        }

        return result;
    }

    /** Outcome of one term lookup: the term, a definitive miss, or a transport failure. */
    private record TermFetch(String iri, OlsTerm term, boolean transportFailure, String message) {
    }

    private TermFetch fetchTerm(String iri) {
        try {
            var doubleEncoded = java.net.URLEncoder.encode(iri, java.nio.charset.StandardCharsets.UTF_8);
            doubleEncoded = java.net.URLEncoder.encode(doubleEncoded, java.nio.charset.StandardCharsets.UTF_8);

            var found = urlToJson(getOlsUrl() + "/api/terms/findByIdAndIsDefiningOntology/" + doubleEncoded);

            if (found == null ||
                !found.getAsJsonObject().has("_embedded") ||
                !found.getAsJsonObject().get("_embedded").getAsJsonObject().has("terms") ||
                found.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray().size() == 0) {
                return new TermFetch(iri, null, false, null);
            }
            var terms = found.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();
            return new TermFetch(iri, gson.fromJson(terms.get(0), OlsTerm.class), false, null);
        } catch (CachedHttpClient.HttpStatusException e) {
            // 404 is OLS saying "no such term"; anything else is the service misbehaving
            if (e.statusCode == 404) return new TermFetch(iri, null, false, null);
            System.err.println("Failed to get term from OLS with IRI: " + iri + " - " + e.getMessage());
            return new TermFetch(iri, null, true, e.getMessage());
        } catch (IOException e) {
            System.err.println("Failed to get term from OLS with IRI: " + iri + " - " + e.getMessage());
            return new TermFetch(iri, null, true, e.getMessage());
        }
    }

    /** Records a degradation for the property being mapped, unless the request was simply cancelled. */
    private static void warn(String what, Exception e) {
        if (uk.ac.ebi.zooma2.util.RequestCancellation.isCancelled()) return;
        uk.ac.ebi.zooma2.util.Diagnostics.warn(what + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
    }

    /**
     * Classes semantically similar to a term, from OLS's llm_similar endpoint,
     * through the cached, cancellable HTTP client like every other OLS call
     * (so responses are cached, visible to the offline test harness, and aborted
     * on client disconnect), bounded by the similar-request semaphore.
     */
    public List<OlsTerm> findSimilarTerms(String termIri, String model, int size) {
        try {
            similarSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        try {
            // Double URL encode the IRI as required by OLS V2 API
            String encodedIri = java.net.URLEncoder.encode(
                java.net.URLEncoder.encode(termIri, java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
            String url = getOlsUrl() + "/api/v2/classes/" + encodedIri + "/llm_similar?model="
                + java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8) + "&size=" + size;
            var json = urlToJson(url);
            if (json == null || !json.getAsJsonObject().has("elements")) {
                return List.of();
            }
            List<OlsTerm> results = new ArrayList<>();
            for (var element : json.getAsJsonObject().get("elements").getAsJsonArray()) {
                var term = olsTermFromEntity(element.getAsJsonObject());
                if (term.iri != null) results.add(term);
            }
            return results;
        } catch (IOException e) {
            warn("OLS similar-class expansion unavailable", e);
            return List.of();
        } catch (RuntimeException e) {
            warn("OLS similar-class expansion returned an unreadable response", e);
            return List.of();
        } finally {
            similarSemaphore.release();
        }
    }

    /**
     * Get available embedding models from OLS.
     * @return List of model information maps
     */
    public List<Map<String, Object>> getEmbeddingModels() {
        try {
            var json = urlToJson(getOlsUrl() + "/api/v2/llm_models");
            if (json == null || !json.isJsonArray()) {
                System.err.println("Failed to get embedding models from OLS");
                return List.of();
            }
            List<Map<String, Object>> models = gson.fromJson(json, new TypeToken<List<Map<String, Object>>>(){}.getType());
            System.err.println("Found " + models.size() + " embedding models in OLS");
            return models;
        } catch (IOException e) {
            System.err.println("Error getting embedding models from OLS: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Get the default embedding model (first one with can_embed: true).
     * @return Model name or null if none available
     */
    public String getDefaultEmbeddingModel() {
        var models = getEmbeddingModels();
        for (var model : models) {
            Object canEmbed = model.get("can_embed");
            if (canEmbed instanceof Boolean && (Boolean) canEmbed) {
                Object modelName = model.get("model");
                if (modelName instanceof String) {
                    System.err.println("Default embedding model: " + modelName);
                    return (String) modelName;
                }
            }
        }
        System.err.println("No embedding model with can_embed=true found");
        return null;
    }

    /**
     * Search OLS entities using embedding similarity.
     * Uses the /api/v2/entities/embedding_search endpoint.
     * @param query The text query to search for
     * @param model The embedding model name to use
     * @param ontologyId Optional ontology ID to filter results
     * @param size Number of results to return
     * @return Collection of matching OLS terms
     */
    public Collection<OlsTerm> findByEmbeddingSearch(String query, String model, String ontologyId, int size) {
        return findByEmbeddingSearch(query, model, ontologyId, size, 30000);
    }

    public Collection<OlsTerm> findByEmbeddingSearch(String query, String model, String ontologyId, int size, int timeoutMs) {
        try {
            embeddingSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        try {
            var encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            var encodedModel = java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8);
            
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(getOlsUrl())
                      .append("/api/v2/entities/llm_search?q=")
                      .append(encodedQuery)
                      .append("&model=")
                      .append(encodedModel)
                      .append("&size=")
                      .append(size);
            
            if (ontologyId != null && !ontologyId.isEmpty()) {
                urlBuilder.append("&ontologyId=").append(java.net.URLEncoder.encode(ontologyId, java.nio.charset.StandardCharsets.UTF_8));
            }
            
            String url = urlBuilder.toString();
            System.err.println("Embedding search URL: " + url);
            
            var json = urlToJson(url, timeoutMs);
            
            if (json == null) {
                System.err.println("No response from OLS embedding search");
                return List.of();
            }
            
            // Handle V2PagedResponse structure: { elements: [...], page: {...} }
            if (!json.getAsJsonObject().has("elements")) {
                System.err.println("No elements found in OLS embedding search response");
                return List.of();
            }
            
            var elements = json.getAsJsonObject().get("elements").getAsJsonArray();
            System.err.println("Found " + elements.size() + " entities in OLS embedding search");
            
            // Convert V2Entity format to OlsTerm
            List<OlsTerm> results = new ArrayList<>();
            for (var element : elements) {
                var term = olsTermFromEntity(element.getAsJsonObject());
                results.add(term);
            }
            
            // Deliberately NOT saved to the term cache: an entity-derived term carries
            // is_obsolete but never term_replaced_by / annotation, and saveTerms fully
            // replaces the row, so it would clobber a complete record with a partial one
            // that then passes isCompleteCacheEntry and can never be re-fetched. Callers
            // consume these terms directly via Annotation.resolvedTerm.
            return results;
            
        } catch (IOException e) {
            warn("OLS embedding search unavailable", e);
            return List.of();
        } catch (RuntimeException e) {
            warn("OLS embedding search returned an unreadable response", e);
            return List.of();
        } finally {
            embeddingSemaphore.release();
        }
    }

    private OlsTerm olsTermFromEntity(JsonObject obj) {
        OlsTerm term = new OlsTerm();
        term.iri = firstStringMember(obj, "iri");
        term.label = firstStringMember(obj, "label");
        term.short_form = firstStringMember(obj, "shortForm", "short_form");
        term.ontology_name = firstStringMember(obj, "ontologyId", "ontology_name");
        term.synonyms = stringListMember(obj, "synonyms");
        term.is_obsolete = firstBooleanMember(obj, "isObsolete", "is_obsolete");
        term.score = firstDoubleMember(obj, "score");
        return term;
    }

    private String firstStringMember(JsonObject obj, String... names) {
        for (String name : names) {
            if (obj.has(name)) {
                var value = firstStringValue(obj.get(name));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String firstStringValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            for (var value : element.getAsJsonArray()) {
                var stringValue = firstStringValue(value);
                if (stringValue != null && !stringValue.isBlank()) {
                    return stringValue;
                }
            }
            return null;
        }
        if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            var preferred = firstStringMember(obj, "label", "value", "@value", "literal", "text", "name");
            if (preferred != null) {
                return preferred;
            }
        }
        return null;
    }

    private List<String> stringListMember(JsonObject obj, String name) {
        if (!obj.has(name)) {
            return null;
        }
        var element = obj.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            var value = firstStringValue(element);
            return value == null ? null : List.of(value);
        }

        List<String> values = new ArrayList<>();
        for (var item : element.getAsJsonArray()) {
            var value = firstStringValue(item);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : values;
    }

    private Boolean firstBooleanMember(JsonObject obj, String... names) {
        for (String name : names) {
            if (obj.has(name)) {
                var value = obj.get(name);
                if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                    return value.getAsBoolean();
                }
            }
        }
        return null;
    }

    private Double firstDoubleMember(JsonObject obj, String... names) {
        for (String name : names) {
            if (obj.has(name)) {
                var value = obj.get(name);
                if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                    return value.getAsDouble();
                }
            }
        }
        return null;
    }

    /** Largest tag_text request body, in bytes of joined input text; bigger batches are split. */
    public static final int TAG_TEXT_MAX_CHUNK_BYTES = 64 * 1024;
    /** Most input terms per tag_text request. */
    public static final int TAG_TEXT_MAX_CHUNK_TERMS = 500;

    /**
     * Result of a bulk tag_text call: matches per input term, plus the terms whose
     * request failed even after a retry. A failed chunk degrades only its own
     * terms; the caller reports the failure on those properties instead of
     * silently returning them without their exact-match tier.
     */
    public static final class TagTextResponse {
        public final Map<String, List<TagTextMatch>> matches;
        public final Set<String> failedTerms;
        public final String failure;

        public TagTextResponse(Map<String, List<TagTextMatch>> matches, Set<String> failedTerms, String failure) {
            this.matches = matches;
            this.failedTerms = failedTerms;
            this.failure = failure;
        }
    }

    /**
     * Use OLS text tagger (Aho-Corasick) to find exact lexical matches for multiple terms.
     * The terms are joined with newlines and POSTed to /api/v2/tag_text in chunks of at
     * most {@link #TAG_TEXT_MAX_CHUNK_BYTES} / {@link #TAG_TEXT_MAX_CHUNK_TERMS}, run
     * concurrently, each retried once on a transient failure; results are mapped back
     * to the input terms. A batch that fits in one chunk produces exactly the request
     * a single call always did.
     *
     * @param terms List of input terms to match
     * @param ontologyIds Optional list of ontology IDs to restrict to
     */
    public TagTextResponse tagText(List<String> terms, List<String> ontologyIds) {
        if (terms == null || terms.isEmpty()) {
            return new TagTextResponse(Map.of(), Set.of(), null);
        }
        List<List<String>> chunks = chunkTerms(terms, TAG_TEXT_MAX_CHUNK_BYTES, TAG_TEXT_MAX_CHUNK_TERMS);
        if (chunks.size() == 1) {
            return tagTextChunk(chunks.get(0), ontologyIds);
        }

        final java.util.concurrent.atomic.AtomicBoolean cancelFlag = uk.ac.ebi.zooma2.util.RequestCancellation.getFlag();
        Map<String, List<TagTextMatch>> matches = new HashMap<>();
        Set<String> failedTerms = new HashSet<>();
        String failure = null;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<TagTextResponse>> futures = chunks.stream()
                .map(chunk -> executor.submit(() -> {
                    if (cancelFlag != null) uk.ac.ebi.zooma2.util.RequestCancellation.setFlag(cancelFlag);
                    return tagTextChunk(chunk, ontologyIds);
                }))
                .toList();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    TagTextResponse chunkResponse = futures.get(i).get();
                    matches.putAll(chunkResponse.matches);
                    failedTerms.addAll(chunkResponse.failedTerms);
                    if (chunkResponse.failure != null && failure == null) failure = chunkResponse.failure;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    failedTerms.addAll(chunks.get(i));
                    if (failure == null) failure = String.valueOf(e.getCause());
                }
            }
        }
        return new TagTextResponse(matches, failedTerms, failure);
    }

    /** Splits terms in order into chunks bounded by joined UTF-8 size and count; an oversized term gets its own chunk. */
    static List<List<String>> chunkTerms(List<String> terms, int maxBytes, int maxTerms) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentBytes = 0;
        for (String term : terms) {
            int bytes = String.valueOf(term).getBytes(StandardCharsets.UTF_8).length + 1;
            if (!current.isEmpty() && (current.size() >= maxTerms || currentBytes + bytes > maxBytes)) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(term);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    private TagTextResponse tagTextChunk(List<String> terms, List<String> ontologyIds) {
        // OLS tag_text returns UTF-8 byte offsets. Keep the joined text and
        // term boundaries in bytes until the HTTP request body needs a String.
        var joinedText = joinTermsAsUtf8(terms);
        String text = joinedText.asString();

        // Build URL with query params
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(getOlsUrl()).append("/api/v2/tag_text?includeSubstrings=true");
        // word-boundary delimiters so only whole tokens match
        urlBuilder.append("&delimiters=").append(
            java.net.URLEncoder.encode(" ,.;:!?\t\n()[]{}\"'/\\-_", java.nio.charset.StandardCharsets.UTF_8));
        urlBuilder.append("&minLength=").append(minMatchLengthFor(terms));
        if (ontologyIds != null) {
            for (String ont : ontologyIds) {
                urlBuilder.append("&ontologyId=").append(
                    java.net.URLEncoder.encode(ont, java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        try {
            String body = gson.toJson(Map.of("text", text));
            var json = postJsonToUrl(urlBuilder.toString(), body);

            if (json == null || !json.getAsJsonObject().has("entities")) {
                System.err.println("No entities in tag_text response");
                return new TagTextResponse(Map.of(), Set.of(), null);
            }

            var entities = json.getAsJsonObject().get("entities").getAsJsonArray();
            System.err.println("tag_text returned " + entities.size() + " entity hits for " + terms.size() + " terms");

            // Group results by which input term they fall within
            Map<String, List<TagTextMatch>> results = new HashMap<>();
            for (var entity : entities) {
                var obj = entity.getAsJsonObject();
                int startByte = obj.get("start").getAsInt();
                int endByte = obj.get("end").getAsInt();
                String termLabel = obj.has("term_label") ? obj.get("term_label").getAsString() : null;
                String termIri = obj.has("term_iri") ? obj.get("term_iri").getAsString() : null;
                String ontologyId = obj.has("ontology_id") ? obj.get("ontology_id").getAsString() : null;
                String stringType = obj.has("string_type") ? obj.get("string_type").getAsString() : null;
                String source = obj.has("source") && !obj.get("source").isJsonNull() ? obj.get("source").getAsString() : null;
                String shortForm = obj.has("short_form") ? obj.get("short_form").getAsString() :
                                   (obj.has("shortForm") ? obj.get("shortForm").getAsString() : null);
                List<String> synonyms = null;
                if (obj.has("synonyms") && obj.get("synonyms").isJsonArray()) {
                    synonyms = gson.fromJson(obj.get("synonyms"), new TypeToken<List<String>>(){}.getType());
                }
                Boolean isObsolete = null;
                if (obj.has("is_obsolete") && !obj.get("is_obsolete").isJsonNull()) {
                    isObsolete = obj.get("is_obsolete").getAsBoolean();
                } else if (obj.has("isObsolete") && !obj.get("isObsolete").isJsonNull()) {
                    isObsolete = obj.get("isObsolete").getAsBoolean();
                }

                // Find which input term this entity belongs to
                for (int i = 0; i < terms.size(); i++) {
                    if (startByte >= joinedText.termStartBytes()[i] && endByte <= joinedText.termEndBytes()[i]) {
                        int matchedLength = endByte - startByte;
                        int termLength = joinedText.termEndBytes()[i] - joinedText.termStartBytes()[i];
                        // The request's minLength is the batch minimum; each term keeps only
                        // matches that clear its own minimum, so a term's results never
                        // depend on which other terms shared the batch.
                        if (!acceptsMatch(terms.get(i), matchedLength)) {
                            break;
                        }
                        double coverage = termLength > 0 ? (double) matchedLength / termLength : 0.0;
                        var match = new TagTextMatch(termLabel, termIri, ontologyId, coverage, stringType, source, shortForm, synonyms, isObsolete);
                        results.computeIfAbsent(terms.get(i), k -> new ArrayList<>()).add(match);
                        break;
                    }
                }
            }

            // Deduplicate by IRI within each term
            for (var entry : results.entrySet()) {
                var seen = new HashSet<String>();
                entry.setValue(entry.getValue().stream()
                    .filter(m -> m.termIri != null && seen.add(m.termIri))
                    .toList());
            }

            return new TagTextResponse(results, Set.of(), null);

        } catch (IOException e) {
            if (!uk.ac.ebi.zooma2.util.RequestCancellation.isCancelled()) {
                System.err.println("Error calling tag_text for " + terms.size() + " terms: " + e.getMessage());
            }
            return new TagTextResponse(Map.of(), new HashSet<>(terms), e.getMessage());
        }
    }

    /**
     * Minimum matched-string length for a tag_text call over discrete input terms.
     *
     * <p>Longer minimums cut noise, but a fixed minimum of 6 silently disabled exact
     * matching for short inputs — common organism names like "rat", "mice" or "yeast"
     * never reached the tagger and fell through to far less precise fuzzy/embedding
     * search. Derive the minimum from the shortest term in the batch instead, capped
     * at the old value of 6 so batches of long terms behave exactly as before.
     * Junk sub-term matches this admits carry proportionally low coverage and are
     * down-weighted or dropped downstream.
     */
    private static int minMatchLengthFor(List<String> terms) {
        int shortest = terms.stream()
            .filter(t -> t != null && !t.isEmpty())
            .mapToInt(String::length)
            .min()
            .orElse(6);
        return Math.max(2, Math.min(6, shortest));
    }

    /** Whether a match of {@code matchedBytes} bytes clears {@code term}'s own minimum (the value a batch of just that term would request). */
    static boolean acceptsMatch(String term, int matchedBytes) {
        return matchedBytes >= minMatchLengthFor(List.of(term));
    }

    /**
     * Result from the OLS text tagger endpoint.
     */
    public static class TagTextMatch {
        public final String termLabel;
        public final String termIri;
        public final String ontologyId;
        public final double coverage; // fraction of input term covered by this match (0..1)
        public final String stringType; // "label", "synonym", or "CURATION"
        public final String source;     // curation source name (e.g. "atlas", "gwas") or null
        public final String shortForm;  // short form (e.g. "EFO_0000305") or null
        public final List<String> synonyms; // synonyms or null
        public final Boolean isObsolete; // whether the term is obsolete, or null if unknown

        public TagTextMatch(String termLabel, String termIri, String ontologyId, double coverage,
                            String stringType, String source, String shortForm, List<String> synonyms,
                            Boolean isObsolete) {
            this.termLabel = termLabel;
            this.termIri = termIri;
            this.ontologyId = ontologyId;
            this.coverage = coverage;
            this.stringType = stringType;
            this.source = source;
            this.shortForm = shortForm;
            this.synonyms = synonyms;
            this.isObsolete = isObsolete;
        }
    }

    private record JoinedUtf8Text(byte[] bytes, int[] termStartBytes, int[] termEndBytes) {
        String asString() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static JoinedUtf8Text joinTermsAsUtf8(List<String> terms) {
        var bytes = new ByteArrayOutputStream();
        int[] termStartBytes = new int[terms.size()];
        int[] termEndBytes = new int[terms.size()];

        for (int i = 0; i < terms.size(); i++) {
            termStartBytes[i] = bytes.size();
            bytes.writeBytes(String.valueOf(terms.get(i)).getBytes(StandardCharsets.UTF_8));
            termEndBytes[i] = bytes.size();
            if (i < terms.size() - 1) {
                bytes.write(TERM_SEPARATOR_BYTE);
            }
        }

        return new JoinedUtf8Text(bytes.toByteArray(), termStartBytes, termEndBytes);
    }

    /**
     * Result from tagWholeText: a tag_text match with its character offsets in the original text
     * and the matched substring.
     */
    public static class WholeTextTagMatch {
        public final int start;
        public final int end;
        public final String matchedText; // substring of the original text that was matched
        public final String termLabel;
        public final String termIri;
        public final String ontologyId;
        public final String stringType;
        public final String source;
        public final String shortForm;
        public final List<String> synonyms;
        public final Boolean isObsolete;

        public WholeTextTagMatch(int start, int end, String matchedText,
                                 String termLabel, String termIri, String ontologyId,
                                 String stringType, String source, String shortForm,
                                 List<String> synonyms, Boolean isObsolete) {
            this.start = start;
            this.end = end;
            this.matchedText = matchedText;
            this.termLabel = termLabel;
            this.termIri = termIri;
            this.ontologyId = ontologyId;
            this.stringType = stringType;
            this.source = source;
            this.shortForm = shortForm;
            this.synonyms = synonyms;
            this.isObsolete = isObsolete;
        }
    }

    /**
     * Use OLS text tagger on a whole body of text (not individual terms).
     * Returns all matches with their character offsets in the original text.
     *
     * @param text The raw input text to tag
     * @param ontologyIds Optional list of ontology IDs to restrict to
     * @return List of matches with character positions in the input text
     */
    public List<WholeTextTagMatch> tagWholeText(String text, List<String> ontologyIds) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        int[] byteToCharIndex = utf8ByteOffsetToCharIndexMap(text);

        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(getOlsUrl()).append("/api/v2/tag_text?includeSubstrings=true");
        urlBuilder.append("&delimiters=").append(
            java.net.URLEncoder.encode(" ,.;:!?\t\n()[]{}\"'/\\-_", java.nio.charset.StandardCharsets.UTF_8));
        urlBuilder.append("&minLength=6");
        if (ontologyIds != null) {
            for (String ont : ontologyIds) {
                urlBuilder.append("&ontologyId=").append(
                    java.net.URLEncoder.encode(ont, java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        try {
            String body = gson.toJson(Map.of("text", new String(textBytes, StandardCharsets.UTF_8)));
            var json = postJsonToUrl(urlBuilder.toString(), body);

            if (json == null || !json.getAsJsonObject().has("entities")) {
                System.err.println("No entities in tag_text (whole text) response");
                return List.of();
            }

            var entities = json.getAsJsonObject().get("entities").getAsJsonArray();
            System.err.println("tag_text (whole text) returned " + entities.size() + " entity hits");

            List<WholeTextTagMatch> results = new ArrayList<>();
            Set<String> seenSpanIri = new HashSet<>();

            for (var entity : entities) {
                var obj = entity.getAsJsonObject();
                int startByte = obj.get("start").getAsInt();
                int endByte = obj.get("end").getAsInt();
                int start = byteOffsetToCharIndex(byteToCharIndex, startByte);
                int end = byteOffsetToCharIndex(byteToCharIndex, endByte);
                String termLabel = obj.has("term_label") ? obj.get("term_label").getAsString() : null;
                String termIri = obj.has("term_iri") ? obj.get("term_iri").getAsString() : null;
                String ontologyId = obj.has("ontology_id") ? obj.get("ontology_id").getAsString() : null;
                String stringType = obj.has("string_type") ? obj.get("string_type").getAsString() : null;
                String source = obj.has("source") && !obj.get("source").isJsonNull() ? obj.get("source").getAsString() : null;
                String shortForm = obj.has("short_form") ? obj.get("short_form").getAsString() :
                                   (obj.has("shortForm") ? obj.get("shortForm").getAsString() : null);
                List<String> synonyms = null;
                if (obj.has("synonyms") && obj.get("synonyms").isJsonArray()) {
                    synonyms = gson.fromJson(obj.get("synonyms"), new TypeToken<List<String>>(){}.getType());
                }
                Boolean isObsolete = null;
                if (obj.has("is_obsolete") && !obj.get("is_obsolete").isJsonNull()) {
                    isObsolete = obj.get("is_obsolete").getAsBoolean();
                } else if (obj.has("isObsolete") && !obj.get("isObsolete").isJsonNull()) {
                    isObsolete = obj.get("isObsolete").getAsBoolean();
                }

                // Deduplicate by (start, end, IRI) to avoid duplicate annotations for the same span
                String key = startByte + ":" + endByte + ":" + termIri;
                if (!seenSpanIri.add(key)) continue;

                String matchedText = text.substring(start, Math.min(end, text.length()));
                results.add(new WholeTextTagMatch(start, end, matchedText,
                    termLabel, termIri, ontologyId, stringType, source, shortForm, synonyms, isObsolete));
            }

            return results;

        } catch (IOException e) {
            System.err.println("Error calling tag_text (whole text): " + e.getMessage());
            return List.of();
        }
    }

    private static int[] utf8ByteOffsetToCharIndexMap(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] byteToCharIndex = new int[bytes.length + 1];
        int byteOffset = 0;

        for (int charIndex = 0; charIndex < text.length();) {
            int nextCharIndex = text.offsetByCodePoints(charIndex, 1);
            int nextByteOffset = byteOffset + text.substring(charIndex, nextCharIndex).getBytes(StandardCharsets.UTF_8).length;
            for (int i = byteOffset; i < nextByteOffset && i < byteToCharIndex.length; i++) {
                byteToCharIndex[i] = charIndex;
            }
            byteOffset = Math.min(nextByteOffset, byteToCharIndex.length - 1);
            charIndex = nextCharIndex;
            byteToCharIndex[byteOffset] = charIndex;
        }

        return byteToCharIndex;
    }

    private static int byteOffsetToCharIndex(int[] byteToCharIndex, int byteOffset) {
        if (byteOffset <= 0) {
            return 0;
        }
        if (byteOffset >= byteToCharIndex.length) {
            return byteToCharIndex[byteToCharIndex.length - 1];
        }
        return byteToCharIndex[byteOffset];
    }

    /**
     * Fetch the list of curation source names from OLS.
     * These correspond to the datasource names (atlas, gwas, sysmicro, etc.).
     */
    public List<String> getCurationSources() throws IOException {
        var json = urlToJson(getOlsUrl() + "/api/v2/curation_sources");
        if (json == null || !json.isJsonArray()) {
            System.err.println("Failed to get curation sources from OLS");
            return List.of();
        }
        List<String> sources = gson.fromJson(json, new TypeToken<List<String>>(){}.getType());
        System.err.println("Found " + sources.size() + " curation sources in OLS");
        return sources;
    }

    /**
     * Non-exact fuzzy search using OLS Solr index (edismax).
     * Returns terms with relevance-based scores, capped at maxConfidence.
     * Used by OlsLexicalMatcher for fuzzy/partial matches.
     */
    public List<OlsTerm> findByFuzzySearch(String query, int size) {
        return findByFuzzySearch(query, size, 60000);
    }

    public List<OlsTerm> findByFuzzySearch(String query, int size, int timeoutMs) {
        try {
            lexicalSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        try {
        var escaped = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        var url = getOlsUrl() + "/api/v2/entities?search=" + escaped + "&exactMatch=false&size=" + size + "&type=class";

        try {
            var json = urlToJson(url, timeoutMs);
            if (json == null || !json.getAsJsonObject().has("elements")) {
                return List.of();
            }
            var elements = json.getAsJsonObject().get("elements").getAsJsonArray();
            if (elements.size() == 0) return List.of();

            List<OlsTerm> results = new ArrayList<>();
            for (var element : elements) {
                var term = olsTermFromEntity(element.getAsJsonObject());
                results.add(term);
            }
            return results;
        } catch (IOException e) {
            warn("OLS lexical search unavailable", e);
            return List.of();
        } catch (RuntimeException e) {
            warn("OLS lexical search returned an unreadable response", e);
            return List.of();
        }
        } finally {
            lexicalSemaphore.release();
        }
    }

    private JsonElement postJsonToUrl(String url, String jsonBody) throws IOException {
        return CachedHttpClient.postJson(url, jsonBody, 30000);
    }

    private JsonElement urlToJson(String url) throws IOException {
        return CachedHttpClient.getJson(url, 30000);
    }

    private JsonElement urlToJson(String url, int timeoutMs) throws IOException {
        return CachedHttpClient.getJson(url, timeoutMs);
    }
}
