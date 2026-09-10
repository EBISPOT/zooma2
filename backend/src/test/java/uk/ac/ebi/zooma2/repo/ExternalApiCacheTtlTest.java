package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.OlsTerm;

/** Cached OLS data must expire (issue #15, point 1), unless the TTL is 0. */
class ExternalApiCacheTtlTest {

    private static ZoomaDatabase freshDb() throws Exception {
        Path file = Files.createTempFile("zooma-ttl-", ".db");
        file.toFile().deleteOnExit();
        return new ZoomaDatabase("jdbc:sqlite:" + file, null, null);
    }

    private static void backdate(ZoomaDatabase db, String table, int days) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE " + table + " SET created_at = datetime('now', '-" + days + " days')");
        }
    }

    @Test
    void expiredResponsesAreMissesAndArePurged() throws Exception {
        ZoomaDatabase db = freshDb();
        ExternalApiCache cache = new ExternalApiCache(db, 7 * 24 * 3600);
        cache.put("GET", "https://ols/x", null, "{\"a\":1}", null, 200);
        assertNotNull(cache.get("GET", "https://ols/x", null), "fresh entry hits");

        backdate(db, "external_api_cache", 8);
        assertNull(cache.get("GET", "https://ols/x", null), "an entry older than the TTL is a miss");

        assertEquals(1, cache.purgeExpired());
        assertEquals(0, cache.purgeExpired(), "nothing left to purge");
    }

    @Test
    void ttlZeroNeverExpiresAndNeverPurges() throws Exception {
        ZoomaDatabase db = freshDb();
        ExternalApiCache cache = new ExternalApiCache(db, 0);
        cache.put("GET", "https://ols/x", null, "{\"a\":1}", null, 200);
        backdate(db, "external_api_cache", 3650);
        assertNotNull(cache.get("GET", "https://ols/x", null), "test suites rely on a decade-old cache");
        assertEquals(0, cache.purgeExpired());
    }

    @Test
    void refetchResetsTheAge() throws Exception {
        ZoomaDatabase db = freshDb();
        ExternalApiCache cache = new ExternalApiCache(db, 7 * 24 * 3600);
        cache.put("GET", "https://ols/x", null, "{\"a\":1}", null, 200);
        backdate(db, "external_api_cache", 8);
        cache.put("GET", "https://ols/x", null, "{\"a\":2}", null, 200);
        assertEquals("{\"a\":2}", cache.get("GET", "https://ols/x", null).body);
    }

    @Test
    void cachedTermsExpireToo() throws Exception {
        ZoomaDatabase db = freshDb();
        OlsTermCache terms = new OlsTermCache(db, 7 * 24 * 3600);
        OlsTerm t = new OlsTerm();
        t.iri = "http://www.ebi.ac.uk/efo/EFO_0000400";
        t.label = "x";
        t.is_obsolete = false;
        terms.saveTerms(List.of(t));
        assertEquals(1, terms.getTerms(List.of(t.iri)).size());
        backdate(db, "ols_terms", 8);
        assertEquals(0, terms.getTerms(List.of(t.iri)).size(), "stale term is re-fetched, not replayed");
        assertNull(terms.getTerm(t.iri));
        assertEquals(1, new ExternalApiCache(db, 7 * 24 * 3600).purgeExpired());
    }
}
