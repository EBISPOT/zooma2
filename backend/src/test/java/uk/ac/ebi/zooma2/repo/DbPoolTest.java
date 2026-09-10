package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/** Cache operations share a pool of WAL-mode connections and survive concurrent use (issue #22, point 4). */
class DbPoolTest {

    @Test
    void concurrentReadsAndWritesThroughThePool() throws Exception {
        Path file = Files.createTempFile("zooma-pool-", ".db");
        file.toFile().deleteOnExit();
        try (ZoomaDatabase db = new ZoomaDatabase("jdbc:sqlite:" + file, null, null, 4)) {
            try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase());
            }
            ExternalApiCache cache = new ExternalApiCache(db, 0);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 16; t++) {
                    final int thread = t;
                    futures.add(executor.submit(() -> {
                        for (int i = 0; i < 20; i++) {
                            String url = "https://ols/" + thread + "/" + i;
                            cache.put("GET", url, null, "{\"i\":" + i + "}", null, 200);
                            assertNotNull(cache.get("GET", url, null));
                            cache.get("GET", "https://ols/" + ((thread + 1) % 16) + "/" + i, null);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) f.get();
            }
            try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM external_api_cache")) {
                rs.next();
                assertEquals(320, rs.getInt(1));
            }
        }
    }
}
