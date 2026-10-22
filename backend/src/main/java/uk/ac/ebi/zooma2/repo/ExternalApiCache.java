package uk.ac.ebi.zooma2.repo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Caches external HTTP API responses (OLS, OXO, bioregistry) in the unified
 * zooma database. Keyed by (method, url, request_body).
 *
 * <p>When an unexpired cached response exists it is returned without making an
 * HTTP call. This allows the test suite to run fully offline with a
 * pre-populated cache. Entries older than the configured TTL (see
 * {@link CacheTtl}) are treated as misses and overwritten by the next fetch;
 * {@link #purgeExpired()} removes them, and {@link #enforceSizeCap(long)} drops
 * the oldest rows once the table outgrows a byte ceiling, so the database
 * cannot grow without bound.
 *
 * <p>Bodies are stored gzipped. A deep OLS embedding search returns 2-4 MB of
 * JSON that compresses about tenfold; stored verbatim, the prod cache grew by
 * 14 GB a day and filled a node's disk. Rows written before compression hold
 * plain text and still read back.
 *
 * <p>Deletes run in batches of {@link #DELETE_BATCH} rows, each its own
 * transaction, so the SQLite write lock is held for milliseconds. The purge
 * used to be one {@code DELETE ... WHERE created_at < ...} over an unindexed
 * column: with bodies of megabytes it read the whole file (139 GB on prod)
 * while holding the lock, and every cache write meanwhile timed out with
 * SQLITE_BUSY.
 */
public class ExternalApiCache {

    /** Ceiling on the cache's size in bytes; {@code 0} disables it. SQLite only. */
    public static final String MAX_BYTES_ENV = "ZOOMA2_CACHE_MAX_BYTES";
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024 * 1024;

    /** Rows deleted per statement by the purge. */
    static final int DELETE_BATCH = 500;
    /** Rows deleted per statement by the size cap: smaller, so it trims about what is needed and no more. */
    static final int CAP_BATCH = 50;

    private final ZoomaDatabase db;
    private final long ttlSeconds;
    private boolean warnedNoCapOnPostgres;

    public ExternalApiCache(ZoomaDatabase db) {
        this(db, CacheTtl.fromEnvironment());
    }

    public ExternalApiCache(ZoomaDatabase db, long ttlSeconds) {
        this.db = db;
        this.ttlSeconds = ttlSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public static long maxBytesFromEnvironment() {
        String raw = System.getenv(MAX_BYTES_ENV);
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
        try {
            long bytes = Long.parseLong(raw.trim());
            if (bytes < 0) throw new NumberFormatException("negative");
            return bytes;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid " + MAX_BYTES_ENV + "='" + raw + "'; using " + DEFAULT_MAX_BYTES);
            return DEFAULT_MAX_BYTES;
        }
    }

    /**
     * Look up an unexpired cached response.
     * @return The cached response body, or null if not cached (or expired).
     */
    public CachedResponse get(String method, String url, String requestBody) {
        String fresh = CacheTtl.freshnessPredicate(db, ttlSeconds);
        String sql = "SELECT response_body, response_headers, status_code FROM external_api_cache "
            + "WHERE method = ? AND url = ? AND coalesce(request_body, '') = coalesce(?, '')"
            + (fresh != null ? " AND " + fresh : "");

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, method);
            ps.setString(2, url);
            ps.setString(3, requestBody);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CachedResponse(
                        decodeBody(rs.getBytes("response_body")),
                        rs.getString("response_headers"),
                        rs.getInt("status_code")
                    );
                }
            }
            return null;
        } catch (SQLException e) {
            System.err.println("ExternalApiCache lookup error: " + e.getMessage());
            return null;
        } catch (IOException e) {
            // A corrupt stored body is a miss; the refetch overwrites it.
            System.err.println("ExternalApiCache decode error for " + method + " " + url + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Store a response in the cache (replacing any previous entry and resetting its age).
     */
    public void put(String method, String url, String requestBody,
                    String responseBody, String responseHeaders, int statusCode) {
        String sql = db.isPostgres()
            ? "INSERT INTO external_api_cache (method, url, request_body, response_body, response_headers, status_code) "
              + "VALUES (?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (method, url, coalesce(request_body, '')) DO UPDATE SET "
              + "response_body = EXCLUDED.response_body, response_headers = EXCLUDED.response_headers, "
              + "status_code = EXCLUDED.status_code, created_at = NOW()"
            : "INSERT OR REPLACE INTO external_api_cache (method, url, request_body, response_body, response_headers, status_code) "
              + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, method);
            ps.setString(2, url);
            ps.setString(3, requestBody);
            ps.setBytes(4, encodeBody(responseBody));
            ps.setString(5, responseHeaders);
            ps.setInt(6, statusCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("ExternalApiCache store error: " + e.getMessage());
        }
    }

    /**
     * Remove a cached entry (e.g. when it contains invalid data).
     */
    public void evict(String method, String url, String requestBody) {
        String sql = "DELETE FROM external_api_cache WHERE method = ? AND url = ? AND coalesce(request_body, '') = coalesce(?, '')";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, method);
            ps.setString(2, url);
            ps.setString(3, requestBody);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                System.err.println("Evicted bad cache entry: " + method + " " + url);
            }
        } catch (SQLException e) {
            System.err.println("ExternalApiCache evict error: " + e.getMessage());
        }
    }

    /**
     * Deletes entries older than the TTL from every cache table, in batches. A
     * no-op when the TTL is 0.
     * @return number of rows deleted
     */
    public int purgeExpired() {
        if (ttlSeconds <= 0) return 0;
        int deleted = 0;
        try (Connection conn = db.getConnection()) {
            for (String[] tableAndColumn : new String[][] {{"external_api_cache", "created_at"}, {"ols_terms", "created_at"}, {"ols_failed_iris", "failed_at"}}) {
                String expired = CacheTtl.expiryPredicate(db, ttlSeconds, tableAndColumn[1]);
                int n;
                do {
                    n = deleteBatch(conn, tableAndColumn[0], expired, null, DELETE_BATCH);
                    deleted += n;
                    if (n == DELETE_BATCH) yieldToWriters();
                } while (n == DELETE_BATCH);
            }
        } catch (SQLException e) {
            System.err.println("ExternalApiCache purge error: " + e.getMessage());
        }
        return deleted;
    }

    /**
     * Drops the oldest cached responses until the database is 10% under
     * {@code maxBytes} (the slack means a tick that trims need not be followed
     * by one on every later tick). SQLite only: on PostgreSQL deleted rows free
     * no space until vacuum, so a size loop there would empty the table.
     * @return number of rows deleted
     */
    public int enforceSizeCap(long maxBytes) {
        if (maxBytes <= 0) return 0;
        if (db.isPostgres()) {
            if (!warnedNoCapOnPostgres) {
                System.err.println("Cache size cap is not enforced on PostgreSQL; rely on the TTL");
                warnedNoCapOnPostgres = true;
            }
            return 0;
        }
        int deleted = 0;
        try (Connection conn = db.getConnection()) {
            long used = usedBytes(conn);
            if (used <= maxBytes) return 0;
            long target = maxBytes - maxBytes / 10;
            while (used > target) {
                int n = deleteBatch(conn, "external_api_cache", null, "created_at, rowid", CAP_BATCH);
                if (n == 0) break;
                deleted += n;
                used = usedBytes(conn);
                yieldToWriters();
            }
            System.err.println("Cache size cap: dropped " + deleted + " oldest responses; "
                + used + " of " + maxBytes + " bytes in use");
        } catch (SQLException e) {
            System.err.println("ExternalApiCache size cap error: " + e.getMessage());
        }
        return deleted;
    }

    /** Bytes of the database in use (SQLite: pages not on the free list). */
    public long usedBytes() {
        try (Connection conn = db.getConnection()) {
            return usedBytes(conn);
        } catch (SQLException e) {
            System.err.println("ExternalApiCache size error: " + e.getMessage());
            return 0;
        }
    }

    private long usedBytes(Connection conn) throws SQLException {
        if (db.isPostgres()) {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT pg_total_relation_size('external_api_cache')")) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
        return (pragma(conn, "page_count") - pragma(conn, "freelist_count")) * pragma(conn, "page_size");
    }

    private static long pragma(Connection conn, String name) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /**
     * Deletes up to {@code limit} rows of {@code table} matching {@code where}
     * (all rows when null), the first by {@code orderBy} when given, as one
     * short transaction.
     */
    private int deleteBatch(Connection conn, String table, String where, String orderBy, int limit) throws SQLException {
        String rowRef = db.isPostgres() ? "ctid" : "rowid";
        String sql = "DELETE FROM " + table + " WHERE " + rowRef + " IN (SELECT " + rowRef + " FROM " + table
            + (where != null ? " WHERE " + where : "")
            + (orderBy != null ? " ORDER BY " + orderBy : "")
            + " LIMIT " + limit + ")";
        try (Statement s = conn.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    /** Between batches, let a cache write that is polling for the lock get in first. */
    private static void yieldToWriters() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static byte[] encodeBody(String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length() / 8));
        try (GZIPOutputStream gz = new GZIPOutputStream(out, 64 * 1024)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("gzip of an in-memory string failed", e);
        }
        return out.toByteArray();
    }

    /** Gunzips a stored body; bytes that are not gzip are a pre-compression plain-text row. */
    static String decodeBody(byte[] stored) throws IOException {
        if (stored == null) return null;
        if (stored.length >= 2 && (stored[0] & 0xff) == 0x1f && (stored[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(stored), 64 * 1024)) {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(stored, StandardCharsets.UTF_8);
    }

    public static class CachedResponse {
        public final String body;
        public final String headers;
        public final int statusCode;

        public CachedResponse(String body, String headers, int statusCode) {
            this.body = body;
            this.headers = headers;
            this.statusCode = statusCode;
        }
    }
}
