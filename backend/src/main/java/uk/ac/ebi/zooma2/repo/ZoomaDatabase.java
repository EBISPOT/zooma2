package uk.ac.ebi.zooma2.repo;

import java.sql.*;

/**
 * Unified database for Zooma. Manages a single SQLite (dev/test) or PostgreSQL (prod) database
 * with tables: votes, embeddings, ols_terms, ols_failed_iris, external_api_cache.
 *
 * Configure via env vars:
 *   ZOOMA2_DB_URL  – JDBC URL (default: jdbc:sqlite:zooma.db)
 *   ZOOMA2_DB_USER – username (PostgreSQL only)
 *   ZOOMA2_DB_PASS – password (PostgreSQL only)
 */
public class ZoomaDatabase {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final boolean isPostgres;

    public ZoomaDatabase() {
        this(
            System.getenv().getOrDefault("ZOOMA2_DB_URL", "jdbc:sqlite:zooma.db"),
            System.getenv("ZOOMA2_DB_USER"),
            System.getenv("ZOOMA2_DB_PASS")
        );
    }

    public ZoomaDatabase(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.isPostgres = jdbcUrl.startsWith("jdbc:postgresql");
        initAllTables();
    }

    public Connection getConnection() throws SQLException {
        if (user != null) {
            return DriverManager.getConnection(jdbcUrl, user, password);
        }
        return DriverManager.getConnection(jdbcUrl);
    }

    public boolean isPostgres() {
        return isPostgres;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    private void initAllTables() {
        String autoIncrement = isPostgres ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String timestampDefault = isPostgres ? "TIMESTAMP DEFAULT NOW()" : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        String insertOrReplace = isPostgres ? "INSERT" : "INSERT OR REPLACE";
        String insertOrIgnore = isPostgres ? "INSERT" : "INSERT OR IGNORE";
        String blobType = isPostgres ? "BYTEA" : "BLOB";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            // --- votes ---
            stmt.execute("CREATE TABLE IF NOT EXISTS votes ("
                + "id " + autoIncrement + ", "
                + "property_value TEXT NOT NULL, "
                + "property_type TEXT, "
                + "term_id TEXT NOT NULL, "
                + "term_label TEXT, "
                + "ontology TEXT, "
                + "vote TEXT NOT NULL, "
                + "created_at " + timestampDefault
                + ")");

            // --- embeddings ---
            stmt.execute("CREATE TABLE IF NOT EXISTS embeddings ("
                + "text TEXT NOT NULL, "
                + "model TEXT NOT NULL, "
                + "source_type TEXT NOT NULL, "
                + "embedding " + blobType + " NOT NULL, "
                + "created_at " + timestampDefault + ", "
                + "PRIMARY KEY (text, model, source_type)"
                + ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_lookup ON embeddings(text, model, source_type)");

            // --- ols_terms ---
            stmt.execute("CREATE TABLE IF NOT EXISTS ols_terms ("
                + "iri TEXT PRIMARY KEY, "
                + "short_form TEXT, "
                + "label TEXT, "
                + "ontology_name TEXT, "
                + "is_obsolete INTEGER, "
                + "term_replaced_by TEXT, "
                + "synonyms_json TEXT, "
                + "term_json TEXT NOT NULL, "
                + "created_at " + timestampDefault
                + ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ols_terms_short_form ON ols_terms(short_form)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ols_terms_ontology ON ols_terms(ontology_name)");

            // --- ols_failed_iris ---
            stmt.execute("CREATE TABLE IF NOT EXISTS ols_failed_iris ("
                + "iri TEXT PRIMARY KEY, "
                + "failed_at " + timestampDefault
                + ")");

            // --- external_api_cache ---
            stmt.execute("CREATE TABLE IF NOT EXISTS external_api_cache ("
                + "method TEXT NOT NULL, "
                + "url TEXT NOT NULL, "
                + "request_body TEXT, "
                + "response_body TEXT NOT NULL, "
                + "response_headers TEXT, "
                + "status_code INTEGER DEFAULT 200, "
                + "created_at " + timestampDefault
                + ")");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_api_cache_lookup "
                + "ON external_api_cache(method, url, coalesce(request_body, ''))");

            System.err.println("ZoomaDatabase initialized: " + jdbcUrl);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize ZoomaDatabase", e);
        }
    }
}
