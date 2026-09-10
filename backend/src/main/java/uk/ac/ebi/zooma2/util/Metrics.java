package uk.ac.ebi.zooma2.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for the external calls the service depends on, exposed
 * at {@code /v3/api/metrics}. The System.err diagnostics tell a story per
 * request; these tell whether the cache is working, how often OLS fails and how
 * often a retry saved a call, without grepping logs.
 */
public final class Metrics {

    public static final AtomicLong HTTP_REQUESTS = new AtomicLong();
    public static final AtomicLong HTTP_FAILURES = new AtomicLong();
    public static final AtomicLong HTTP_RETRIES = new AtomicLong();
    public static final AtomicLong CACHE_HITS = new AtomicLong();
    public static final AtomicLong CACHE_MISSES = new AtomicLong();
    public static final AtomicLong WARNINGS = new AtomicLong();

    private Metrics() {
    }

    public static Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("httpRequests", HTTP_REQUESTS.get());
        m.put("httpFailures", HTTP_FAILURES.get());
        m.put("httpRetries", HTTP_RETRIES.get());
        m.put("cacheHits", CACHE_HITS.get());
        m.put("cacheMisses", CACHE_MISSES.get());
        m.put("warnings", WARNINGS.get());
        return m;
    }

    /** For tests. */
    public static void reset() {
        for (AtomicLong c : new AtomicLong[] {HTTP_REQUESTS, HTTP_FAILURES, HTTP_RETRIES, CACHE_HITS, CACHE_MISSES, WARNINGS}) c.set(0);
    }
}
