package uk.ac.ebi.zooma2.repo;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores user votes (thumbs-up / thumbs-down) on mapping results.
 * Supports SQLite (default for dev) and PostgreSQL (for prod).
 *
 * Configure via env vars:
 *   ZOOMA2_VOTES_DB_URL  – JDBC URL (default: jdbc:sqlite:votes.db)
 *   ZOOMA2_VOTES_DB_USER – username (PostgreSQL only)
 *   ZOOMA2_VOTES_DB_PASS – password (PostgreSQL only)
 */
public class VoteRepository {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final boolean isPostgres;

    public VoteRepository() {
        this(
            System.getenv().getOrDefault("ZOOMA2_VOTES_DB_URL", "jdbc:sqlite:votes.db"),
            System.getenv("ZOOMA2_VOTES_DB_USER"),
            System.getenv("ZOOMA2_VOTES_DB_PASS")
        );
    }

    public VoteRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.isPostgres = jdbcUrl.startsWith("jdbc:postgresql");
        initSchema();
    }

    private Connection getConnection() throws SQLException {
        if (user != null) {
            return DriverManager.getConnection(jdbcUrl, user, password);
        }
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() {
        String autoIncrement = isPostgres ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String timestampDefault = isPostgres ? "TIMESTAMP DEFAULT NOW()" : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        String sql = "CREATE TABLE IF NOT EXISTS votes ("
            + "id " + autoIncrement + ", "
            + "property_value TEXT NOT NULL, "
            + "property_type TEXT, "
            + "term_id TEXT NOT NULL, "
            + "term_label TEXT, "
            + "ontology TEXT, "
            + "vote TEXT NOT NULL, "
            + "created_at " + timestampDefault
            + ")";
        try (var conn = getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize votes database", e);
        }
    }

    public void recordVote(String propertyValue, String propertyType, String termId,
                           String termLabel, String ontology, String vote) {
        String sql = "INSERT INTO votes (property_value, property_type, term_id, term_label, ontology, vote) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, propertyValue);
            ps.setString(2, propertyType);
            ps.setString(3, termId);
            ps.setString(4, termLabel);
            ps.setString(5, ontology);
            ps.setString(6, vote);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record vote", e);
        }
    }

    public List<Vote> getVotes(String propertyValue) {
        String sql = "SELECT id, property_value, property_type, term_id, term_label, ontology, vote, created_at "
            + "FROM votes WHERE property_value = ? ORDER BY created_at DESC";
        List<Vote> votes = new ArrayList<>();
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, propertyValue);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    votes.add(new Vote(
                        rs.getLong("id"),
                        rs.getString("property_value"),
                        rs.getString("property_type"),
                        rs.getString("term_id"),
                        rs.getString("term_label"),
                        rs.getString("ontology"),
                        rs.getString("vote"),
                        rs.getString("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get votes", e);
        }
        return votes;
    }

    public record Vote(
        long id,
        String propertyValue,
        String propertyType,
        String termId,
        String termLabel,
        String ontology,
        String vote,
        String createdAt
    ) {}
}
