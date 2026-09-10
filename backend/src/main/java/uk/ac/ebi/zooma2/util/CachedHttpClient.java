package uk.ac.ebi.zooma2.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import uk.ac.ebi.zooma2.repo.ExternalApiCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

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
        if (RequestCancellation.isCancelled()) {
            throw new IOException("HTTP request cancelled: client disconnected");
        }
        if (apiCache != null) {
            var cached = apiCache.get("GET", url, null);
            if (cached != null) {
                try {
                    return parseJsonStrict(cached.body, "GET", url);
                } catch (IOException e) {
                    System.err.println("Evicting bad cached entry for GET " + url + ": " + e.getMessage());
                    apiCache.evict("GET", url, null);
                }
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        try (CloseableHttpClient client = HttpClientBuilder.create().useSystemProperties().setDefaultRequestConfig(config).build()) {
            HttpGet request = new HttpGet(url);
            Thread watcher = abortOnCancellation(request);
            try {
                HttpResponse response = client.execute(request);
                int statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                    if (statusCode < 200 || statusCode >= 300) {
                        throw new IOException("HTTP " + statusCode + " for GET " + url + ": " + body.substring(0, Math.min(body.length(), 500)));
                    }
                    var parsed = parseJsonStrict(body, "GET", url);
                    if (apiCache != null) {
                        apiCache.put("GET", url, null, body, null, statusCode);
                    }
                    return parsed;
                } else {
                    throw new IOException("Response was null for GET " + url);
                }
            } finally {
                if (watcher != null) watcher.interrupt();
            }
        }
    }

    /**
     * POST JSON to a URL and parse response as JSON, with caching.
     */
    public static JsonElement postJson(String url, String jsonBody, int timeoutMs) throws IOException {
        if (RequestCancellation.isCancelled()) {
            throw new IOException("HTTP request cancelled: client disconnected");
        }
        if (apiCache != null) {
            var cached = apiCache.get("POST", url, jsonBody);
            if (cached != null) {
                try {
                    return parseJsonStrict(cached.body, "POST", url);
                } catch (IOException e) {
                    System.err.println("Evicting bad cached entry for POST " + url + ": " + e.getMessage());
                    apiCache.evict("POST", url, jsonBody);
                }
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
            // The bulk tag_text POST is the largest single request in the pipeline; it
            // must abort on client disconnect just like the GETs.
            Thread watcher = abortOnCancellation(request);
            try {
                HttpResponse response = client.execute(request);
                int statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                    if (statusCode < 200 || statusCode >= 300) {
                        throw new IOException("HTTP " + statusCode + " for POST " + url + ": " + body.substring(0, Math.min(body.length(), 500)));
                    }
                    var parsed = parseJsonStrict(body, "POST", url);
                    if (apiCache != null) {
                        apiCache.put("POST", url, jsonBody, body, null, statusCode);
                    }
                    return parsed;
                } else {
                    throw new IOException("Response was null for POST " + url);
                }
            } finally {
                if (watcher != null) watcher.interrupt();
            }
        }
    }

    /**
     * Aborts {@code request} and interrupts the calling thread as soon as the
     * current request's cancellation flag is set (a streaming client
     * disconnected), so an in-flight OLS call ends immediately instead of
     * running to completion or timeout for a response nobody will read.
     *
     * @return the watcher thread, which the caller must interrupt once the
     *         request has completed, or {@code null} if no flag is registered
     */
    static Thread abortOnCancellation(HttpRequestBase request) {
        AtomicBoolean flag = RequestCancellation.getFlag();
        if (flag == null) return null;
        Thread callerThread = Thread.currentThread();
        return Thread.ofVirtual().start(() -> {
            while (!flag.get()) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
            request.abort();
            callerThread.interrupt();
        });
    }

    /**
     * GET a URL and parse as JSON, using system properties for proxy, with caching.
     */
    public static JsonElement getJsonWithSystemProperties(String url, int timeoutMs) throws IOException {
        if (apiCache != null) {
            var cached = apiCache.get("GET", url, null);
            if (cached != null) {
                try {
                    return parseJsonStrict(cached.body, "GET", url);
                } catch (IOException e) {
                    System.err.println("Evicting bad cached entry for GET " + url + ": " + e.getMessage());
                    apiCache.evict("GET", url, null);
                }
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        try (CloseableHttpClient client = HttpClientBuilder.create().useSystemProperties().setDefaultRequestConfig(config).build()) {
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                if (statusCode < 200 || statusCode >= 300) {
                    throw new IOException("HTTP " + statusCode + " for GET " + url + ": " + body.substring(0, Math.min(body.length(), 500)));
                }
                var parsed = parseJsonStrict(body, "GET", url);
                if (apiCache != null) {
                    apiCache.put("GET", url, null, body, null, statusCode);
                }
                return parsed;
            } else {
                throw new IOException("Response was null for GET " + url);
            }
        }
    }

    /**
     * Parse a string as JSON, validating that it looks like JSON first.
     * Throws IOException with a clear message if the body is not valid JSON.
     */
    private static JsonElement parseJsonStrict(String body, String method, String url) throws IOException {
        if (body == null || body.isEmpty()) {
            throw new IOException("Empty response body for " + method + " " + url);
        }
        String trimmed = body.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith("\"")) {
            throw new IOException("Response is not JSON for " + method + " " + url + ": " + body.substring(0, Math.min(body.length(), 200)));
        }
        try {
            return new JsonParser().parse(body);
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("Malformed JSON for " + method + " " + url + ": " + e.getMessage());
        }
    }

}
