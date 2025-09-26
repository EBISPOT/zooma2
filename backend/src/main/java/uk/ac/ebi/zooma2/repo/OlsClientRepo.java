package uk.ac.ebi.zooma2.repo;

import java.io.IOException;
import java.io.InputStreamReader;
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

        Map<String, OlsTerm> res = new HashMap<>();

        for(String iri : termIris) {
            try {

                var doubleEncoded = java.net.URLEncoder.encode(iri, java.nio.charset.StandardCharsets.UTF_8);
                doubleEncoded = java.net.URLEncoder.encode(doubleEncoded, java.nio.charset.StandardCharsets.UTF_8);

                var found = urlToJson(OLS_URL + "/api/terms/findByIdAndIsDefiningOntology/" + doubleEncoded);

                var terms = found.getAsJsonObject().get("_embedded").getAsJsonObject().get("terms").getAsJsonArray();

                res.put(iri, gson.fromJson(terms.get(0), OlsTerm.class));

            } catch (IOException e) {
                throw new RuntimeException("Failed to resolve term IRI: " + iri, e);
            }
        }

        return res;
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
