package uk.ac.ebi.zooma2.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonParser;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import uk.ac.ebi.zooma2.repo.ExternalApiCache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP utility with transparent caching via ExternalApiCache.
 * When a cache is set, responses are stored and replayed on subsequent calls.
 * This allows offline testing with a pre-populated database.
 */
public class CachedHttpClient {

    private static ExternalApiCache apiCache;

    public static void setApiCache(ExternalApiCache cache) {
        apiCache = cache;
    }

    public static ExternalApiCache getApiCache() {
        return apiCache;
    }

    /**
     * GET a URL and parse as JSON, with caching.
     */
    public static JsonElement getJson(String url, int timeoutMs) throws IOException {
        if (apiCache != null) {
            var cached = apiCache.get("GET", url, null);
            if (cached != null) {
                return new JsonParser().parse(cached.body);
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        try (CloseableHttpClient client = HttpClientBuilder.create().useSystemProperties().setDefaultRequestConfig(config).build()) {
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                if (apiCache != null) {
                    apiCache.put("GET", url, null, body, null, response.getStatusLine().getStatusCode());
                }
                return new JsonParser().parse(body);
            } else {
                throw new IOException("Response was null for GET " + url);
            }
        }
    }

    /**
     * POST JSON to a URL and parse response as JSON, with caching.
     */
    public static JsonElement postJson(String url, String jsonBody, int timeoutMs) throws IOException {
        if (apiCache != null) {
            var cached = apiCache.get("POST", url, jsonBody);
            if (cached != null) {
                return new JsonParser().parse(cached.body);
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        try (CloseableHttpClient client = HttpClientBuilder.create().useSystemProperties().setDefaultRequestConfig(config).build()) {
            HttpPost request = new HttpPost(url);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(jsonBody, "UTF-8"));
            HttpResponse response = client.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                if (apiCache != null) {
                    apiCache.put("POST", url, jsonBody, body, null, response.getStatusLine().getStatusCode());
                }
                return new JsonParser().parse(body);
            } else {
                throw new IOException("Response was null for POST " + url);
            }
        }
    }

    /**
     * POST JSON to a URL and get raw binary response, with caching.
     * Used for embedding service which returns binary float arrays.
     * Caches the response as base64-encoded body plus headers as JSON.
     */
    public static BinaryResponse postBinary(String url, String jsonBody, int timeoutMs) throws IOException {
        if (apiCache != null) {
            var cached = apiCache.get("POST_BINARY", url, jsonBody);
            if (cached != null) {
                byte[] data = java.util.Base64.getDecoder().decode(cached.body);
                Map<String, String> headers = new HashMap<>();
                if (cached.headers != null && !cached.headers.isEmpty()) {
                    var gson = new com.google.gson.Gson();
                    @SuppressWarnings("unchecked")
                    Map<String, String> parsed = gson.fromJson(cached.headers, Map.class);
                    if (parsed != null) headers.putAll(parsed);
                }
                return new BinaryResponse(data, headers, cached.statusCode);
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        try (CloseableHttpClient client = HttpClientBuilder.create().useSystemProperties().setDefaultRequestConfig(config).build()) {
            HttpPost request = new HttpPost(url);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(jsonBody, "UTF-8"));
            HttpResponse response = client.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                byte[] data = EntityUtils.toByteArray(entity);
                int statusCode = response.getStatusLine().getStatusCode();

                Map<String, String> headers = new HashMap<>();
                for (var header : response.getAllHeaders()) {
                    headers.put(header.getName(), header.getValue());
                }

                if (apiCache != null) {
                    String base64 = java.util.Base64.getEncoder().encodeToString(data);
                    var gson = new com.google.gson.Gson();
                    apiCache.put("POST_BINARY", url, jsonBody, base64, gson.toJson(headers), statusCode);
                }

                return new BinaryResponse(data, headers, statusCode);
            } else {
                throw new IOException("Response was null for POST " + url);
            }
        }
    }

    /**
     * GET a URL and parse as JSON, using system properties for proxy, with caching.
     */
    public static JsonElement getJsonWithSystemProperties(String url, int timeoutMs) throws IOException {
        if (apiCache != null) {
            var cached = apiCache.get("GET", url, null);
            if (cached != null) {
                return new JsonParser().parse(cached.body);
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        try (CloseableHttpClient client = HttpClientBuilder.create().useSystemProperties().setDefaultRequestConfig(config).build()) {
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                if (apiCache != null) {
                    apiCache.put("GET", url, null, body, null, response.getStatusLine().getStatusCode());
                }
                return new JsonParser().parse(body);
            } else {
                throw new IOException("Response was null for GET " + url);
            }
        }
    }

    public static class BinaryResponse {
        public final byte[] data;
        public final Map<String, String> headers;
        public final int statusCode;

        public BinaryResponse(byte[] data, Map<String, String> headers, int statusCode) {
            this.data = data;
            this.headers = headers;
            this.statusCode = statusCode;
        }

        public String getHeader(String name) {
            return headers != null ? headers.get(name) : null;
        }
    }
}
