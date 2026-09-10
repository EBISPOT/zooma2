package uk.ac.ebi.zooma2.repo;

import java.sql.*;

/**
 * Caches external HTTP API responses (OLS, OXO, bioregistry) in the unified
 * zooma database. Keyed by (method, url, request_body).
 *
 * <p>When an unexpired cached response exists it is returned without making an
 * HTTP call. This allows the test suite to run fully offline with a
 * pre-populated cache. Entries older than the configured TTL (see
 * {@link CacheTtl}) are treated as misses and overwritten by the next fetch;
 * {@link #purgeExpired()} removes them so the table does not grow without bound.
 */
public class ExternalApiCache {

    private final ZoomaDatabase db;
    private final long ttlSeconds;

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
                        rs.getString("response_body"),
                        rs.getString("response_headers"),
                        rs.getInt("status_code")
                    );
                }
            }
            return null;
        } catch (SQLException e) {
            System.err.println("ExternalApiCache lookup error: " + e.getMessage());
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
            ps.setString(4, responseBody);
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
     * Deletes entries older than the TTL from every cache table. A no-op when the
     * TTL is 0.
     * @return number of rows deleted
     */
    public int purgeExpired() {
        if (ttlSeconds <= 0) return 0;
        int deleted = 0;
        try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement()) {
            for (String[] tableAndColumn : new String[][] {{"external_api_cache", "created_at"}, {"ols_terms", "created_at"}, {"ols_failed_iris", "failed_at"}}) {
                deleted += stmt.executeUpdate("DELETE FROM " + tableAndColumn[0] + " WHERE "
                    + CacheTtl.expiryPredicate(db, ttlSeconds, tableAndColumn[1]));
            }
        } catch (SQLException e) {
            System.err.println("ExternalApiCache purge error: " + e.getMessage());
        }
        return deleted;
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
