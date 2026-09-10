package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** One retry for transient failures, none for definitive ones (issue #15, point 2). */
class HttpRetryTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private int firstStatus;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = hits.incrementAndGet();
            int status = n == 1 ? firstStatus : 200;
            byte[] body = (status == 200 ? "{\"ok\":true}" : "{\"error\":\"nope\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/x";
    }

    @Test
    void a503IsRetriedOnceAndSucceeds() throws IOException {
        firstStatus = 503;
        var json = CachedHttpClient.getJson(url(), 5000);
        assertTrue(json.getAsJsonObject().get("ok").getAsBoolean());
        assertEquals(2, hits.get());
    }

    @Test
    void a404IsNotRetried() {
        firstStatus = 404;
        var e = assertThrows(CachedHttpClient.HttpStatusException.class, () -> CachedHttpClient.postJson(url(), "{}", 5000));
        assertEquals(404, e.statusCode);
        assertEquals(1, hits.get());
    }

    @Test
    void aSecondFailureIsReported() {
        firstStatus = 500;
        // make every response fail: swap the handler
        server.removeContext("/");
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        var e = assertThrows(CachedHttpClient.HttpStatusException.class, () -> CachedHttpClient.getJson(url(), 5000));
        assertEquals(502, e.statusCode);
        assertEquals(2, hits.get(), "exactly one retry");
    }
}
