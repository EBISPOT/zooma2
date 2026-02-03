package uk.ac.ebi.zooma2.repo;

import java.sql.*;
import java.util.*;

import com.google.gson.Gson;

import uk.ac.ebi.zooma2.model.OlsTerm;

/**
 * SQLite cache for OLS term lookups.
 * Caches term metadata (label, short_form, synonyms, etc.) by IRI.
 */
public class OlsTermCache {

    private final String jdbcUrl;
    private final Gson gson = new Gson();

    /**
     * Create an OLS term cache with SQLite.
     */
    public static OlsTermCache createSqliteCache(String dbPath) {
        return new OlsTermCache("jdbc:sqlite:" + dbPath);
    }

    private OlsTermCache(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initializeDatabase();
    }

    private void initializeDatabase() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS ols_terms (
                iri TEXT PRIMARY KEY,
                short_form TEXT,
                label TEXT,
                ontology_name TEXT,
                is_obsolete INTEGER,
                term_replaced_by TEXT,
                synonyms_json TEXT,
                term_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;

        String createFailedTableSQL = """
            CREATE TABLE IF NOT EXISTS ols_failed_iris (
                iri TEXT PRIMARY KEY,
                failed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            stmt.execute(createFailedTableSQL);
            
            // Create indexes for faster lookups
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ols_terms_short_form ON ols_terms(short_form)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ols_terms_ontology ON ols_terms(ontology_name)");
            
            System.err.println("OLS term cache database initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize OLS term cache database", e);
        }
    }

    /**
     * Get cached term by IRI.
     * @return OlsTerm or null if not cached
     */
    public OlsTerm getTerm(String iri) {
        String sql = "SELECT term_json FROM ols_terms WHERE iri = ?";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, iri);
            
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String json = rs.getString("term_json");
                return gson.fromJson(json, OlsTerm.class);
            }
            return null;
        } catch (SQLException e) {
            System.err.println("Error retrieving term from cache: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get multiple cached terms by IRI.
     * @return Map of IRI to OlsTerm for found terms
     */
    public Map<String, OlsTerm> getTerms(Collection<String> iris) {
        if (iris == null || iris.isEmpty()) {
            return Map.of();
        }

        Map<String, OlsTerm> result = new HashMap<>();
        
        // Build query with placeholders
        String placeholders = String.join(",", Collections.nCopies(iris.size(), "?"));
        String sql = "SELECT iri, term_json FROM ols_terms WHERE iri IN (" + placeholders + ")";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int i = 1;
            for (String iri : iris) {
                stmt.setString(i++, iri);
            }
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String iri = rs.getString("iri");
                String json = rs.getString("term_json");
                OlsTerm term = gson.fromJson(json, OlsTerm.class);
                result.put(iri, term);
            }
        } catch (SQLException e) {
            System.err.println("Error retrieving terms from cache: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Save term to cache.
     */
    public void saveTerm(OlsTerm term) {
        if (term == null || term.iri == null) {
            return;
        }

        String sql = """
            INSERT OR REPLACE INTO ols_terms 
            (iri, short_form, label, ontology_name, is_obsolete, term_replaced_by, synonyms_json, term_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, term.iri);
            stmt.setString(2, term.short_form);
            stmt.setString(3, term.label);
            stmt.setString(4, term.ontology_name);
            stmt.setInt(5, term.isObsolete() ? 1 : 0);
            stmt.setString(6, term.term_replaced_by);
            stmt.setString(7, term.synonyms != null ? gson.toJson(term.synonyms) : null);
            stmt.setString(8, gson.toJson(term));
            
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving term to cache: " + e.getMessage());
        }
    }

    /**
     * Save multiple terms to cache.
     */
    public void saveTerms(Collection<OlsTerm> terms) {
        if (terms == null || terms.isEmpty()) {
            return;
        }

        String sql = """
            INSERT OR REPLACE INTO ols_terms 
            (iri, short_form, label, ontology_name, is_obsolete, term_replaced_by, synonyms_json, term_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            for (OlsTerm term : terms) {
                if (term == null || term.iri == null) {
                    continue;
                }
                
                stmt.setString(1, term.iri);
                stmt.setString(2, term.short_form);
                stmt.setString(3, term.label);
                stmt.setString(4, term.ontology_name);
                stmt.setInt(5, term.isObsolete() ? 1 : 0);
                stmt.setString(6, term.term_replaced_by);
                stmt.setString(7, term.synonyms != null ? gson.toJson(term.synonyms) : null);
                stmt.setString(8, gson.toJson(term));
                
                stmt.addBatch();
            }
            
            stmt.executeBatch();
        } catch (SQLException e) {
            System.err.println("Error saving terms to cache: " + e.getMessage());
        }
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM ols_terms");
            if (rs.next()) {
                stats.put("totalTerms", rs.getInt("count"));
            }
            
            rs = stmt.executeQuery("SELECT ontology_name, COUNT(*) as count FROM ols_terms GROUP BY ontology_name ORDER BY count DESC LIMIT 10");
            List<Map<String, Object>> ontologies = new ArrayList<>();
            while (rs.next()) {
                ontologies.add(Map.of(
                    "ontology", rs.getString("ontology_name"),
                    "count", rs.getInt("count")
                ));
            }
            stats.put("topOntologies", ontologies);
            
        } catch (SQLException e) {
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }

    /**
     * Get IRIs that previously failed to resolve (for warmup filtering).
     */
    public Set<String> getFailedIris() {
        Set<String> failed = new HashSet<>();
        String sql = "SELECT iri FROM ols_failed_iris";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                failed.add(rs.getString("iri"));
            }
        } catch (SQLException e) {
            System.err.println("Error retrieving failed IRIs: " + e.getMessage());
        }
        
        return failed;
    }

    /**
     * Mark an IRI as failed (not found in OLS).
     */
    public void markFailed(String iri) {
        if (iri == null) return;
        
        String sql = "INSERT OR IGNORE INTO ols_failed_iris (iri) VALUES (?)";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, iri);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error marking IRI as failed: " + e.getMessage());
        }
    }

    /**
     * Mark multiple IRIs as failed.
     */
    public void markFailed(Collection<String> iris) {
        if (iris == null || iris.isEmpty()) return;
        
        String sql = "INSERT OR IGNORE INTO ols_failed_iris (iri) VALUES (?)";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            for (String iri : iris) {
                if (iri != null) {
                    stmt.setString(1, iri);
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            System.err.println("Error marking IRIs as failed: " + e.getMessage());
        }
    }
}
