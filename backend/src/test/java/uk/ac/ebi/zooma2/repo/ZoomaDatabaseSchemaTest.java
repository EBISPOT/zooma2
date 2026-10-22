package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The cache tables are indexed by age, and pooled SQLite connections run WAL with synchronous=NORMAL. */
class ZoomaDatabaseSchemaTest {

    private static Set<String> indexes(Connection c, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA index_list('" + table + "')")) {
            while (rs.next()) names.add(rs.getString("name"));
        }
        return names;
    }

    @Test
    void ageColumnsAreIndexedAndConnectionsAreSynchronousNormal() throws Exception {
        Path file = Files.createTempFile("zooma-schema-", ".db");
        file.toFile().deleteOnExit();
        try (ZoomaDatabase db = new ZoomaDatabase("jdbc:sqlite:" + file, null, null, 2)) {
            try (Connection c = db.getConnection()) {
                assertTrue(indexes(c, "external_api_cache").contains("idx_api_cache_created"));
                assertTrue(indexes(c, "ols_terms").contains("idx_ols_terms_created"));
                assertTrue(indexes(c, "ols_failed_iris").contains("idx_ols_failed_at"));
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA synchronous")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "1 = NORMAL");
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA busy_timeout")) {
                    assertTrue(rs.next());
                    assertEquals(5000, rs.getInt(1));
                }
            }
        }
    }
}
