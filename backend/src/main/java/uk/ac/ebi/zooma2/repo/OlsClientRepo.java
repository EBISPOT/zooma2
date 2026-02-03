package uk.ac.ebi.zooma2.repo;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import uk.ac.ebi.zooma2.model.OlsTerm;

public class OlsClientRepo {

    Gson gson = new Gson();

    public static final String OLS_URL = System.getenv().getOrDefault("ZOOMA2_OLS_URL", "https://wwwdev.ebi.ac.uk/ols4");

    private OlsTermCache termCache;

    public void setTermCache(OlsTermCache cache) {
        this.termCache = cache;
    }

    public OlsTermCache getTermCache() {
        return termCache;
    }

    public List<OlsOntology> getOntologies() throws IOException {

        var json = urlToJson(OLS_URL + "/api/ontologies?size=1000");
        json = json.getAsJsonObject().get("_embedded").getAsJsonObject().get("ontologies");

        List<OlsOntology> ontologies = gson.fromJson(json, new TypeToken<List<OlsOntology>>(){}.getType());

        if(ontologies == null) {
            throw new RuntimeException("Failed to load ontologies from OLS");
        }

        return ontologies;
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
            result.putAll(cached);
            
            // Find IRIs not in cache
            for (String iri : termIris) {
                if (!cached.containsKey(iri)) {
                    irisToFetch.add(iri);
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

        // Fetch remaining from OLS (filter out nulls)
        var resolved = irisToFetch
            .stream()
            .filter(iri -> iri != null && !iri.isEmpty())
            .parallel()
            .map((String iri) -> {

                try {

                    var doubleEncoded = java.net.URLEncoder.encode(iri, java.nio.charset.StandardCharsets.UTF_8);
                    doubleEncoded = java.net.URLEncoder.encode(doubleEncoded, java.nio.charset.StandardCharsets.UTF_8);

                    var found = urlToJson(OLS_URL + "/api/terms/findByIdAndIsDefiningOntology/" + doubleEncoded);

                    if(found == null ||
                        !found.getAsJsonObject().has("_embedded") ||
                        !found.getAsJsonObject().get("_embedded").getAsJsonObject().has("terms") ||
                        found.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray().size() == 0) {

                        // System.err.println("Failed to get term from OLS (1) with IRI: " + iri);
                        return null;
                    }


                    var terms = found.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();

                    return gson.fromJson(terms.get(0), OlsTerm.class);

                } catch (IOException e) {
                    System.err.println("Failed to get term from OLS (2) with IRI: " + iri + " - " + e.getMessage());
                    return null;
                }

            })
            .filter(t -> t != null)
            .toList();

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
        
        if (termCache != null) {
            // Mark IRIs that we tried but failed to resolve
            Set<String> failedIris = new HashSet<>(fetchedIris);
            failedIris.removeAll(resolvedIris);
            if (!failedIris.isEmpty()) {
                termCache.markFailed(failedIris);
            }
        }

        return result;
    }

    public Collection<OlsTerm> findByLabelAndOntologies(String stringToMap, Collection<String> ontologyIds) {

        var escaped = java.net.URLEncoder.encode(stringToMap, java.nio.charset.StandardCharsets.UTF_8);
        var url = OLS_URL + "/api/search?q=" + escaped + "&exact=true";

        if (ontologyIds != null) {
            for(var ont : ontologyIds) {
                url += "&ontology=" + ont;
            }
        }

        System.err.println("Searching OLS for '" + stringToMap + "' in ontologies " + ontologyIds + ", URL: " + url);

        JsonElement found;
        try {
            found = urlToJson(url);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return List.of();
        }

        if(found == null ||
            !found.getAsJsonObject().has("response") ||
            !found.getAsJsonObject().get("response").getAsJsonObject().has("docs") ||
            found.getAsJsonObject().get("response").getAsJsonObject().get("docs").getAsJsonArray().size() == 0) {

            System.err.println("No terms found in OLS for '" + stringToMap + "' in ontologies " + ontologyIds);

            return List.of();
        }

        System.err.println("Found " + found.getAsJsonObject().get("response").getAsJsonObject().get("docs").getAsJsonArray().size() + " terms in OLS for '" + stringToMap + "' in ontologies " + ontologyIds);

        var terms = found.getAsJsonObject().get("response").getAsJsonObject().get("docs").getAsJsonArray();

        List<OlsTerm> res = gson.fromJson(terms, new TypeToken<List<OlsTerm>>(){}.getType());


        // To get the synoynms to check these match we need the full objects
        // TODO (1) fix exact=true in OLS so we can trust it and (2) return synonyms in the search api
        List<String> termIris = res.stream().map((OlsTerm t) -> t.iri).toList();
        Map<String,OlsTerm> fullTerms = resolveTerms(termIris);

        res = fullTerms.values().stream().toList();


        // check it actually matches
        return res.stream()
            .filter(t -> {
                if(t.label != null && t.label.equalsIgnoreCase(stringToMap)) {
                    return true;
                }
                if(t.synonyms != null) {
                    for(String syn : t.synonyms) {
                        if(syn.equalsIgnoreCase(stringToMap)) {
                            return true;
                        }
                    }
                }
                return false;
            })
            .toList();
    }

    /**
     * Find OLS terms by embedding vector using the embedding search endpoint.
     * @param embedding The embedding vector to search with
     * @param ontologyIds Optional list of ontology IDs to filter by
     * @param topK Number of results to return (default 10)
     * @return Collection of matching OLS terms
     */
    public Collection<OlsTerm> findByEmbedding(float[] embedding, Collection<String> ontologyIds, int topK) {
        
        try {
            // Convert float[] to List for JSON serialization
            List<Float> embeddingList = new ArrayList<>(embedding.length);
            for (float f : embedding) {
                embeddingList.add(f);
            }
            
            // Build the request body with the embedding vector
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("vector", embeddingList);
            if (topK > 0) {
                requestBody.put("limit", topK);
            }
            if (ontologyIds != null && !ontologyIds.isEmpty()) {
                requestBody.put("ontologies", ontologyIds);
            }

            String jsonBody = gson.toJson(requestBody);
            
            // Make POST request to OLS
            var json = postJsonToUrl(OLS_URL + "/api/v2/classes/embedding", jsonBody);
            
            if (json == null || !json.getAsJsonObject().has("_embedded")) {
                System.err.println("No terms found in OLS by embedding");
                return List.of();
            }

            var classes = json.getAsJsonObject().get("_embedded").getAsJsonObject().get("classes");
            if (classes == null || !classes.isJsonArray() || classes.getAsJsonArray().size() == 0) {
                System.err.println("No terms found in OLS by embedding");
                return List.of();
            }

            System.err.println("Found " + classes.getAsJsonArray().size() + " terms in OLS by embedding");

            List<OlsTerm> res = gson.fromJson(classes, new TypeToken<List<OlsTerm>>(){}.getType());
            return res;

        } catch (IOException e) {
            System.err.println("Error searching OLS by embedding: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Get available embedding models from OLS.
     * @return List of model information maps
     */
    public List<Map<String, Object>> getEmbeddingModels() {
        try {
            var json = urlToJson(OLS_URL + "/api/v2/llm_models");
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
        try {
            var encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            var encodedModel = java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8);
            
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(OLS_URL)
                      .append("/api/v2/entities/embedding_search?q=")
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
            
            var json = urlToJson(url);
            
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
                var obj = element.getAsJsonObject();
                OlsTerm term = new OlsTerm();
                term.iri = obj.has("iri") ? obj.get("iri").getAsString() : null;
                
                // Label can be a string or array - handle both
                if (obj.has("label")) {
                    var labelEl = obj.get("label");
                    if (labelEl.isJsonArray() && labelEl.getAsJsonArray().size() > 0) {
                        term.label = labelEl.getAsJsonArray().get(0).getAsString();
                    } else if (labelEl.isJsonPrimitive()) {
                        term.label = labelEl.getAsString();
                    }
                }
                
                term.short_form = obj.has("shortForm") ? obj.get("shortForm").getAsString() : 
                                  (obj.has("short_form") ? obj.get("short_form").getAsString() : null);
                term.ontology_name = obj.has("ontologyId") ? obj.get("ontologyId").getAsString() : 
                                     (obj.has("ontology_name") ? obj.get("ontology_name").getAsString() : null);
                if (obj.has("synonyms") && obj.get("synonyms").isJsonArray()) {
                    term.synonyms = gson.fromJson(obj.get("synonyms"), new TypeToken<List<String>>(){}.getType());
                }
                
                // Capture the similarity score from embedding search
                if (obj.has("score")) {
                    term.score = obj.get("score").getAsDouble();
                }
                
                results.add(term);
            }
            
            // Cache all terms found
            if (termCache != null && !results.isEmpty()) {
                termCache.saveTerms(results);
            }
            
            return results;
            
        } catch (IOException e) {
            System.err.println("Error in OLS embedding search: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Get parent terms for a given term IRI from a specific ontology.
     * @param termIri The IRI of the term to get parents for
     * @param ontologyId The ontology ID (e.g., "mondo", "efo", "snomed")
     * @return List of parent terms
     */
    public List<OlsTerm> getParents(String termIri, String ontologyId) {
        try {
            // Double-encode the IRI as required by OLS API
            var encoded = java.net.URLEncoder.encode(termIri, java.nio.charset.StandardCharsets.UTF_8);
            encoded = java.net.URLEncoder.encode(encoded, java.nio.charset.StandardCharsets.UTF_8);
            
            String url = OLS_URL + "/api/ontologies/" + ontologyId + "/terms/" + encoded + "/parents";
            System.err.println("Getting parents from OLS: " + url);
            
            var json = urlToJson(url);
            
            if (json == null ||
                !json.getAsJsonObject().has("_embedded") ||
                !json.getAsJsonObject().get("_embedded").getAsJsonObject().has("terms")) {
                System.err.println("No parents found for " + termIri + " in " + ontologyId);
                return List.of();
            }
            
            var terms = json.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();
            List<OlsTerm> results = gson.fromJson(terms, new TypeToken<List<OlsTerm>>(){}.getType());
            System.err.println("Found " + results.size() + " parents for " + termIri);
            return results;
            
        } catch (IOException e) {
            System.err.println("Error getting parents from OLS: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Get ancestor closure for a given term IRI from a specific ontology.
     * @param termIri The IRI of the term to get ancestors for
     * @param ontologyId The ontology ID (e.g., "mondo", "efo", "snomed")
     * @return List of ancestor terms (all the way up to root)
     */
    public List<OlsTerm> getAncestors(String termIri, String ontologyId) {
        try {
            // Double-encode the IRI as required by OLS API
            var encoded = java.net.URLEncoder.encode(termIri, java.nio.charset.StandardCharsets.UTF_8);
            encoded = java.net.URLEncoder.encode(encoded, java.nio.charset.StandardCharsets.UTF_8);
            
            String url = OLS_URL + "/api/ontologies/" + ontologyId + "/terms/" + encoded + "/ancestors";
            System.err.println("Getting ancestors from OLS: " + url);
            
            var json = urlToJson(url);
            
            if (json == null ||
                !json.getAsJsonObject().has("_embedded") ||
                !json.getAsJsonObject().get("_embedded").getAsJsonObject().has("terms")) {
                System.err.println("No ancestors found for " + termIri + " in " + ontologyId);
                return List.of();
            }
            
            var terms = json.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();
            List<OlsTerm> results = gson.fromJson(terms, new TypeToken<List<OlsTerm>>(){}.getType());
            System.err.println("Found " + results.size() + " ancestors for " + termIri);
            return results;
            
        } catch (IOException e) {
            System.err.println("Error getting ancestors from OLS: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Get hierarchical ancestors for a given term IRI from a specific ontology.
     * Hierarchical ancestors include part-of relationships, not just rdfs:subClassOf.
     * @param termIri The IRI of the term to get hierarchical ancestors for
     * @param ontologyId The ontology ID (e.g., "mondo", "efo", "snomed")
     * @return List of hierarchical ancestor terms
     */
    public List<OlsTerm> getHierarchicalAncestors(String termIri, String ontologyId) {
        try {
            // Double-encode the IRI as required by OLS API
            var encoded = java.net.URLEncoder.encode(termIri, java.nio.charset.StandardCharsets.UTF_8);
            encoded = java.net.URLEncoder.encode(encoded, java.nio.charset.StandardCharsets.UTF_8);
            
            String url = OLS_URL + "/api/ontologies/" + ontologyId + "/terms/" + encoded + "/hierarchicalAncestors";
            System.err.println("Getting hierarchical ancestors from OLS: " + url);
            
            var json = urlToJson(url);
            
            if (json == null ||
                !json.getAsJsonObject().has("_embedded") ||
                !json.getAsJsonObject().get("_embedded").getAsJsonObject().has("terms")) {
                System.err.println("No hierarchical ancestors found for " + termIri + " in " + ontologyId);
                return List.of();
            }
            
            var terms = json.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();
            List<OlsTerm> results = gson.fromJson(terms, new TypeToken<List<OlsTerm>>(){}.getType());
            System.err.println("Found " + results.size() + " hierarchical ancestors for " + termIri);
            return results;
            
        } catch (IOException e) {
            System.err.println("Error getting hierarchical ancestors from OLS: " + e.getMessage());
            return List.of();
        }
    }

    private JsonElement postJsonToUrl(String url, String jsonBody) throws IOException {

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setSocketTimeout(5000).build();

        CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(config).build();

        org.apache.http.client.methods.HttpPost request = new org.apache.http.client.methods.HttpPost(url);
        request.setHeader("Content-Type", "application/json");
        request.setEntity(new org.apache.http.entity.StringEntity(jsonBody));
        
        HttpResponse response = client.execute(request);
        HttpEntity entity = response.getEntity();
        if (entity != null) {
            return new JsonParser().parse(new InputStreamReader(entity.getContent()));
        } else {
            throw new RuntimeException("OLS response was null");
        }
    }

    private JsonElement urlToJson(String url) throws IOException {

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setSocketTimeout(5000).build();

        CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(config).build();

        HttpGet request = new HttpGet(url);
        HttpResponse response = client.execute(request);
        HttpEntity entity = response.getEntity();
        if (entity != null) {
            return new JsonParser().parse(new InputStreamReader(entity.getContent()));
        } else {
            throw new RuntimeException("bioregistry response was null");
        }
    }
}
