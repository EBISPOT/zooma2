package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The cache must stay bounded. On prod it grew by 14 GB a day of verbatim OLS
 * responses, and the hourly purge scanned the whole file under the write lock.
 */
class ExternalApiCacheSizeTest {

    private static ZoomaDatabase freshDb() throws Exception {
        Path file = Files.createTempFile("zooma-size-", ".db");
        file.toFile().deleteOnExit();
        return new ZoomaDatabase("jdbc:sqlite:" + file, null, null);
    }

    private static long scalar(ZoomaDatabase db, String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    /** ~{@code kilobytes} KB that gzip cannot shrink much, so the size cap test measures real bytes. */
    private static String incompressible(Random random, int kilobytes) {
        byte[] bytes = new byte[kilobytes * 1024 * 3 / 4];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void bodiesAreStoredGzippedAndReadBackIntact() throws Exception {
        ZoomaDatabase db = freshDb();
        ExternalApiCache cache = new ExternalApiCache(db, 0);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) sb.append("{\"iri\":\"http://purl.obolibrary.org/obo/MONDO_").append(i).append("\",\"label\":\"term ").append(i).append("\"},");
        sb.append("{}]");
        String body = sb.toString();
        cache.put("GET", "https://ols/deep", null, body, null, 200);
        assertEquals(body, cache.get("GET", "https://ols/deep", null).body);
        long stored = scalar(db, "SELECT length(response_body) FROM external_api_cache");
        assertTrue(stored < body.length() / 4, "stored " + stored + " bytes for a " + body.length() + " char body");
    }

    /** The integration suite's pre-populated databases predate compression: TEXT column, plain-text rows. */
    @Test
    void databasesFromBeforeCompressionStillWork() throws Exception {
        Path file = Files.createTempFile("zooma-legacy-", ".db");
        file.toFile().deleteOnExit();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE external_api_cache (method TEXT NOT NULL, url TEXT NOT NULL, request_body TEXT, "
                + "response_body TEXT NOT NULL, response_headers TEXT, status_code INTEGER DEFAULT 200, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE UNIQUE INDEX idx_api_cache_lookup ON external_api_cache(method, url, coalesce(request_body, ''))");
            s.execute("INSERT INTO external_api_cache (method, url, response_body) VALUES ('GET', 'https://ols/old', '{\"legacy\":true}')");
        }
        ZoomaDatabase db = new ZoomaDatabase("jdbc:sqlite:" + file, null, null);
        ExternalApiCache cache = new ExternalApiCache(db, 0);
        assertEquals("{\"legacy\":true}", cache.get("GET", "https://ols/old", null).body, "plain-text rows read back");
        cache.put("GET", "https://ols/new", null, "{\"gzipped\":true}", null, 200);
        assertEquals("{\"gzipped\":true}", cache.get("GET", "https://ols/new", null).body, "gzipped bytes fit the TEXT column");
        cache.put("GET", "https://ols/old", null, "{\"legacy\":false}", null, 200);
        assertEquals("{\"legacy\":false}", cache.get("GET", "https://ols/old", null).body, "a refetch replaces a plain-text row");
        assertEquals(1, scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_api_cache_created'"),
            "the age index is added to an existing database");
    }

    @Test
    void purgeDeletesEveryExpiredRowAcrossBatches() throws Exception {
        ZoomaDatabase db = freshDb();
        ExternalApiCache cache = new ExternalApiCache(db, 24 * 3600);
        int rows = 2 * ExternalApiCache.DELETE_BATCH + 17;
        for (int i = 0; i < rows; i++) cache.put("GET", "https://ols/" + i, null, "{}", null, 200);
        cache.put("GET", "https://ols/fresh", null, "{}", null, 200);
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE external_api_cache SET created_at = datetime('now', '-2 days') WHERE url <> 'https://ols/fresh'");
        }
        assertEquals(rows, cache.purgeExpired());
        assertEquals(1, scalar(db, "SELECT count(*) FROM external_api_cache"));
        assertNotNull(cache.get("GET", "https://ols/fresh", null));
    }

    @Test
    void sizeCapDropsTheOldestResponsesFirst() throws Exception {
        ZoomaDatabase db = freshDb();
        ExternalApiCache cache = new ExternalApiCache(db, 0);
        Random random = new Random(42);
        int rows = 4 * ExternalApiCache.CAP_BATCH;
        for (int i = 0; i < rows; i++) {
            cache.put("GET", "https://ols/" + i, null, incompressible(random, 64), null, 200);
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE external_api_cache SET created_at = datetime('now', ?) WHERE url = ?")) {
                ps.setString(1, "-" + (rows - i) + " minutes");
                ps.setString(2, "https://ols/" + i);
                ps.executeUpdate();
            }
        }
        long before = cache.usedBytes();
        // base64 of random bytes gzips to about three quarters: ~48 KB a row
        assertTrue(before > 8_000_000, rows + " x 64 KB of random data uses " + before + " bytes");

        long cap = 4_000_000;
        int dropped = cache.enforceSizeCap(cap);
        assertTrue(dropped > ExternalApiCache.CAP_BATCH && dropped < rows, "dropped " + dropped + " of " + rows);
        assertTrue(cache.usedBytes() <= cap, "under the cap: " + cache.usedBytes());
        assertNull(cache.get("GET", "https://ols/0", null), "the oldest row goes first");
        assertNotNull(cache.get("GET", "https://ols/" + (rows - 1), null), "the newest row stays");
        long remaining = scalar(db, "SELECT count(*) FROM external_api_cache");
        assertEquals(rows - dropped, remaining);
        assertNotNull(cache.get("GET", "https://ols/" + dropped, null), "the rows kept are the newest ones");

        assertEquals(0, cache.enforceSizeCap(cap), "nothing more to do once under the cap");
        assertEquals(0, cache.enforceSizeCap(0), "a cap of 0 is unlimited");
    }
}
