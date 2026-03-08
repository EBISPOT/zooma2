package uk.ac.ebi.zooma2.repo;

import java.sql.*;

/**
 * Caches external HTTP API responses (OLS, OXO, bioregistry, embedding service) in the
 * unified zooma database. Keyed by (method, url, request_body).
 *
 * When a cached response exists it is returned without making an HTTP call.
 * This allows the test suite to run fully offline with a pre-populated cache.
 */
public class ExternalApiCache {

    private final ZoomaDatabase db;

    public ExternalApiCache(ZoomaDatabase db) {
        this.db = db;
    }

    /**
     * Look up a cached response.
     * @return The cached response body, or null if not cached.
     */
    public CachedResponse get(String method, String url, String requestBody) {
        String sql = "SELECT response_body, response_headers, status_code FROM external_api_cache "
            + "WHERE method = ? AND url = ? AND coalesce(request_body, '') = coalesce(?, '')";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, method);
            ps.setString(2, url);
            ps.setString(3, requestBody);

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new CachedResponse(
                    rs.getString("response_body"),
                    rs.getString("response_headers"),
                    rs.getInt("status_code")
                );
            }
            return null;
        } catch (SQLException e) {
            System.err.println("ExternalApiCache lookup error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Store a response in the cache.
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
