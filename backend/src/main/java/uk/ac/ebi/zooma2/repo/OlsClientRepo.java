package uk.ac.ebi.zooma2.repo;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public static final String OLS_URL = System.getenv().getOrDefault("ZOOMA2_OLS_URL", "https://www.ebi.ac.uk/ols4");

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

        // TODO: use bulk OLS API when implemented
        var resolved = termIris
            .stream()
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

                        System.err.println("Failed to get term from OLS (1) with IRI: " + iri);
                        return null;
                    }


                    var terms = found.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();

                    return gson.fromJson(terms.get(0), OlsTerm.class);

                } catch (IOException e) {
                    throw new RuntimeException("Failed to get term from OLS (2) with IRI: " + iri, e);
                }

            })
            .filter(t -> t != null)
            .toList();

        Map<String, OlsTerm> res = new HashMap<>();

        for(var term : resolved) {
            res.put(term.iri, term);
        }

        return res;
    }

    public Collection<OlsTerm> findByLabelAndOntologies(String stringToMap, Collection<String> ontologyIds) {

        var escaped = java.net.URLEncoder.encode(stringToMap, java.nio.charset.StandardCharsets.UTF_8);
        var url = OLS_URL + "/api/search?q=" + escaped + "&exact=true";

        for(var ont : ontologyIds) {
            url += "&ontology=" + ont;
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
     * Find OLS terms by embedding vector using the LLM embedding search endpoint.
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
            var json = postJsonToUrl(OLS_URL + "/api/v2/classes/llm_embedding", jsonBody);
            
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
