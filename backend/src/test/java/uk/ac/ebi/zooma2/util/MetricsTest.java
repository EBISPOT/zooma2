package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import uk.ac.ebi.zooma2.repo.ExternalApiCache;
import uk.ac.ebi.zooma2.repo.ZoomaDatabase;

/** Cache and request counters for /v3/api/metrics (issue #22, point 6). */
class MetricsTest {

    private HttpServer server;
    private ZoomaDatabase db;

    @AfterEach
    void cleanup() {
        CachedHttpClient.setApiCache(null);
        if (server != null) server.stop(0);
        if (db != null) db.close();
    }

    @Test
    void countsRequestsHitsAndMisses() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        Path file = Files.createTempFile("zooma-metrics-", ".db");
        file.toFile().deleteOnExit();
        db = new ZoomaDatabase("jdbc:sqlite:" + file, null, null, 2);
        CachedHttpClient.setApiCache(new ExternalApiCache(db, 0));
        Metrics.reset();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/x";
        CachedHttpClient.getJson(url, 5000);
        CachedHttpClient.getJson(url, 5000);
        CachedHttpClient.getJson(url, 5000);

        var m = Metrics.snapshot();
        assertEquals(1L, m.get("httpRequests"));
        assertEquals(1L, m.get("cacheMisses"));
        assertEquals(2L, m.get("cacheHits"));
        assertEquals(0L, m.get("httpFailures"));
    }
}
