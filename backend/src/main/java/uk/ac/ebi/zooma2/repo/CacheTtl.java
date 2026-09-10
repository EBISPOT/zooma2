package uk.ac.ebi.zooma2.repo;

/**
 * How long cached OLS data (HTTP responses, resolved terms, failed lookups) stays
 * valid. Ontologies are released on a cadence of weeks, so a cache that never
 * expired replayed obsoleted terms and stale labels forever; a cache that
 * expires too quickly loses the latency and offline benefits.
 *
 * <p>Configured with {@code ZOOMA2_CACHE_TTL_SECONDS} (default 30 days).
 * {@code 0} means never expire, which the integration suite uses so its
 * pre-populated cache from a fixed date stays authoritative.
 */
public final class CacheTtl {

    public static final String ENV = "ZOOMA2_CACHE_TTL_SECONDS";
    public static final long DEFAULT_SECONDS = 30L * 24 * 60 * 60;

    private CacheTtl() {
    }

    public static long fromEnvironment() {
        String raw = System.getenv(ENV);
        if (raw == null || raw.isBlank()) return DEFAULT_SECONDS;
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds < 0) throw new NumberFormatException("negative");
            return seconds;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid " + ENV + "='" + raw + "'; using " + DEFAULT_SECONDS);
            return DEFAULT_SECONDS;
        }
    }

    /** SQL predicate on a {@code created_at} column that is true for unexpired rows; {@code null} when nothing expires. */
    static String freshnessPredicate(ZoomaDatabase db, long ttlSeconds) {
        if (ttlSeconds <= 0) return null;
        return db.isPostgres()
            ? "created_at >= NOW() - (" + ttlSeconds + " * interval '1 second')"
            : "created_at >= datetime('now', '-" + ttlSeconds + " seconds')";
    }

    /** SQL predicate that is true for expired rows of a table whose timestamp column is {@code column}; {@code null} when nothing expires. */
    static String expiryPredicate(ZoomaDatabase db, long ttlSeconds, String column) {
        if (ttlSeconds <= 0) return null;
        return db.isPostgres()
            ? column + " < NOW() - (" + ttlSeconds + " * interval '1 second')"
            : column + " < datetime('now', '-" + ttlSeconds + " seconds')";
    }
}
