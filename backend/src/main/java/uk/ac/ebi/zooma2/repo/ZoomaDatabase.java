package uk.ac.ebi.zooma2.repo;

import java.sql.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Unified database for Zooma. Manages a single SQLite (dev/test) or PostgreSQL (prod) database
 * with tables: votes, embeddings, ols_terms, ols_failed_iris, external_api_cache.
 *
 * <p>Connections come from a pool: every cache lookup used to open a fresh JDBC
 * connection (a file open for SQLite, a TCP+auth handshake for PostgreSQL), and
 * a mapping request makes dozens of them. SQLite runs in WAL mode so readers do
 * not block on the cache's writers, with a busy timeout instead of immediate
 * "database is locked" failures.
 *
 * Configure via env vars:
 *   ZOOMA2_DB_URL       – JDBC URL (default: jdbc:sqlite:zooma.db)
 *   ZOOMA2_DB_USER      – username (PostgreSQL only)
 *   ZOOMA2_DB_PASS      – password (PostgreSQL only)
 *   ZOOMA2_DB_POOL_SIZE – connections in the pool (default 8 for SQLite, 16 for PostgreSQL)
 */
public class ZoomaDatabase implements AutoCloseable {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final boolean isPostgres;
    private final HikariDataSource pool;

    public ZoomaDatabase() {
        this(
            System.getenv().getOrDefault("ZOOMA2_DB_URL", "jdbc:sqlite:zooma.db"),
            System.getenv("ZOOMA2_DB_USER"),
            System.getenv("ZOOMA2_DB_PASS")
        );
    }

    public ZoomaDatabase(String jdbcUrl, String user, String password) {
        this(jdbcUrl, user, password, configuredPoolSize(jdbcUrl.startsWith("jdbc:postgresql")));
    }

    public ZoomaDatabase(String jdbcUrl, String user, String password, int poolSize) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.isPostgres = jdbcUrl.startsWith("jdbc:postgresql");
        this.pool = createPool(poolSize);
        initAllTables();
    }

    private HikariDataSource createPool(int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (user != null) {
            config.setUsername(user);
            config.setPassword(password);
        }
        config.setPoolName("zooma-db");
        config.setMaximumPoolSize(Math.max(1, poolSize));
        config.setConnectionTimeout(15_000);
        if (!isPostgres) {
            // Wait for a writer rather than failing immediately under concurrency
            config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
        }
        return new HikariDataSource(config);
    }

    static int configuredPoolSize(boolean postgres) {
        String raw = System.getenv("ZOOMA2_DB_POOL_SIZE");
        int fallback = postgres ? 16 : 8;
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int size = Integer.parseInt(raw.trim());
            if (size < 1 || size > 256) throw new NumberFormatException("out of range");
            return size;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid ZOOMA2_DB_POOL_SIZE='" + raw + "'; using " + fallback);
            return fallback;
        }
    }

    /** A pooled connection; close it to return it to the pool. */
    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    /** Closes the pool. For SQLite this checkpoints the WAL back into the main file. */
    @Override
    public void close() {
        pool.close();
    }

    public boolean isPostgres() {
        return isPostgres;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public long countDistinctEmbeddingTexts() {
        String sql = "SELECT COUNT(DISTINCT text) FROM embeddings";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count embedding texts", e);
        }
    }

    public long countEmbeddingsBySourceType(String sourceType) {
        String sql = "SELECT COUNT(*) FROM embeddings WHERE source_type = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count embeddings by source type", e);
        }
    }

    private void initAllTables() {
        String autoIncrement = isPostgres ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String timestampDefault = isPostgres ? "TIMESTAMP DEFAULT NOW()" : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        String insertOrReplace = isPostgres ? "INSERT" : "INSERT OR REPLACE";
        String insertOrIgnore = isPostgres ? "INSERT" : "INSERT OR IGNORE";
        String blobType = isPostgres ? "BYTEA" : "BLOB";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            if (!isPostgres) {
                // Persistent for the database file; readers no longer block on writers
                stmt.execute("PRAGMA journal_mode = WAL");
            }

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
