package uk.ac.ebi.zooma2;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.plugin.bundled.CorsPluginConfig;
import uk.ac.ebi.zooma2.api.v2.ZoomaApiV2;
import uk.ac.ebi.zooma2.api.v3.ZoomaApiV3;
import uk.ac.ebi.zooma2.nlp.TextSegmenter;
import uk.ac.ebi.zooma2.repo.ExternalApiCache;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsTermCache;
import uk.ac.ebi.zooma2.repo.VoteRepository;
import uk.ac.ebi.zooma2.repo.ZoomaDatabase;
import uk.ac.ebi.zooma2.util.CachedHttpClient;

import java.util.*;

public class ZoomaApp {

    public static void main(String[] args) {

        // Initialize unified database
        var zoomaDb = new ZoomaDatabase();

        // Initialize external API cache for transparent HTTP caching. Entries expire
        // after ZOOMA2_CACHE_TTL_SECONDS (0 = never) so ontology releases reach
        // previously-queried mappings; expired rows are purged at startup and hourly.
        long cacheTtlSeconds = uk.ac.ebi.zooma2.repo.CacheTtl.fromEnvironment();
        var apiCache = new ExternalApiCache(zoomaDb, cacheTtlSeconds);
        CachedHttpClient.setApiCache(apiCache);
        startCacheMaintenance(apiCache);

        var olsLexicalCfg = ZoomaConfig.config.ols_lexical;
        int maxConcurrentLexical = (olsLexicalCfg != null && olsLexicalCfg.max_concurrent_requests != null)
            ? olsLexicalCfg.max_concurrent_requests : 5;
        var olsEmbeddingCfg = ZoomaConfig.config.ols_embedding;
        int maxConcurrentEmbedding = (olsEmbeddingCfg != null && olsEmbeddingCfg.max_concurrent_embedding_requests != null) 
            ? olsEmbeddingCfg.max_concurrent_embedding_requests : 3;
        int maxConcurrentSimilar = (olsEmbeddingCfg != null && olsEmbeddingCfg.max_concurrent_similar_requests != null) 
            ? olsEmbeddingCfg.max_concurrent_similar_requests : 10;
        var olsRepo = new OlsClientRepo(maxConcurrentEmbedding, maxConcurrentSimilar, maxConcurrentLexical);

        // Initialize OLS term cache using unified database
        OlsTermCache olsTermCache = new OlsTermCache(zoomaDb, cacheTtlSeconds);
        olsRepo.setTermCache(olsTermCache);

        var annotator = new ZoomaAnnotator(olsRepo);

        // Initialize NLP text segmenter for annotate-text endpoint
        TextSegmenter textSegmenter;
        try {
            textSegmenter = new TextSegmenter();
            System.out.println("OpenNLP TextSegmenter initialized successfully");
        } catch (java.io.IOException e) {
            System.err.println("Failed to initialize TextSegmenter: " + e.getMessage());
            throw new RuntimeException("Cannot start without NLP models", e);
        }

        var apiV2 = new ZoomaApiV2(annotator, olsRepo);
        var apiV3 = new ZoomaApiV3(annotator, olsRepo, new VoteRepository(zoomaDb), zoomaDb, textSegmenter);

        var app = Javalin.create(config -> {
            config.http.generateEtags = true;
            config.http.maxRequestSize = configuredMaxRequestBytes();
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(ZoomaApp::configureCors);
            });
            config.router.contextPath = System.getenv("ZOOMA2_CONTEXT_PATH");
            if(config.router.contextPath == null) {
                config.router.contextPath = "";
            }

            config.routes.before(ctx -> {
                ctx.header("X-Content-Type-Options", "nosniff");
                ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
                ctx.header("X-Frame-Options", "DENY");
            });

            apiV2.registerRoutes(config.routes);
            apiV3.registerRoutes(config.routes);

            config.routes.exception(BadRequestResponse.class, (e, ctx) -> ctx.status(400).json(Map.of(
                "error", "Bad Request",
                "message", e.getMessage()
            )));
            config.routes.exception(NotFoundResponse.class, (e, ctx) -> ctx.status(404).json(Map.of(
                "error", "Not Found",
                "message", e.getMessage()
            )));
            config.routes.exception(Exception.class, (e, ctx) -> {
                String requestId = UUID.randomUUID().toString();
                System.err.println("Unhandled request error " + requestId + ": " + e.getMessage());
                e.printStackTrace();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "Internal Server Error");
                body.put("message", "Unexpected server error");
                body.put("requestId", requestId);
                ctx.status(500).json(body);
            });
        });

        // Watch config.json for changes and reload automatically
        ZoomaConfig.startConfigWatcher();

        app.start(configuredPort());
    }

    /** Purges expired cache rows now and then every hour, on a daemon thread. */
    private static void startCacheMaintenance(ExternalApiCache apiCache) {
        if (apiCache.getTtlSeconds() <= 0) {
            System.err.println("Cache TTL is 0: cached OLS data never expires");
            return;
        }
        System.err.println("Cache TTL: " + apiCache.getTtlSeconds() + " s");
        Thread maintenance = new Thread(() -> {
            while (true) {
                try {
                    int deleted = apiCache.purgeExpired();
                    if (deleted > 0) System.err.println("Purged " + deleted + " expired cache rows");
                    Thread.sleep(60 * 60 * 1000L);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.err.println("Cache purge failed: " + e.getMessage());
                }
            }
        }, "cache-maintenance");
        maintenance.setDaemon(true);
        maintenance.start();
    }

    // ----------------- DTOs -----------------

    public static final class CollapseRequest {
        public String uri;
    }


    // ----------------- Small helpers -----------------

    private static void notImplemented(Context ctx, Object body) {
        ctx.status(501).json(body);
    }

    private static String q(Context ctx, String name, boolean required) {
        String v = ctx.queryParam(name);
        if (required && (v == null || v.isBlank())) {
            throw new BadRequestResponse("Missing required query parameter: " + name);
        }
        return v;
    }

    private static String path(Context ctx, String name, boolean required) {
        String v = ctx.pathParam(name);
        if (required && (v == null || v.isBlank())) {
            throw new BadRequestResponse("Missing required path parameter: " + name);
        }
        return v;
    }

    private static int queryInt(Context ctx, String name, int def, int min, int max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            int v = Integer.parseInt(raw);
            if (v < min || v > max) throw new NumberFormatException("Out of range");
            return v;
        } catch (NumberFormatException e) {
            throw new BadRequestResponse("Query parameter '" + name + "' must be an integer in [" + min + "," + max + "]");
        }
    }

    private static int configuredPort() {
        String raw = System.getenv().getOrDefault("ZOOMA2_PORT", "8090");
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ZOOMA2_PORT must be an integer in [1,65535], got: " + raw, e);
        }
    }

    private static void configureCors(CorsPluginConfig.CorsRule rule) {
        var allowedOrigins = configuredCorsOrigins();
        if (allowedOrigins.size() == 1 && "*".equals(allowedOrigins.get(0))) {
            rule.anyHost();
            return;
        }
        rule.maxAge = 3600;
        rule.allowHost(allowedOrigins.get(0), allowedOrigins.subList(1, allowedOrigins.size()).toArray(String[]::new));
    }

    private static List<String> configuredCorsOrigins() {
        String raw = System.getenv("ZOOMA2_CORS_ALLOWED_ORIGINS");
        if (raw == null || raw.isBlank()) {
            return List.of(
                "https://www.ebi.ac.uk",
                "https://wwwdev.ebi.ac.uk",
                "http://localhost:3000",
                "http://localhost:8080"
            );
        }
        var origins = Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("ZOOMA2_CORS_ALLOWED_ORIGINS cannot be empty when set");
        }
        return origins;
    }

    private static long configuredMaxRequestBytes() {
        String raw = System.getenv().getOrDefault("ZOOMA2_MAX_REQUEST_BYTES", "2097152");
        try {
            long max = Long.parseLong(raw);
            if (max < 1024 || max > 104_857_600L) {
                throw new NumberFormatException("out of range");
            }
            return max;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ZOOMA2_MAX_REQUEST_BYTES must be an integer in [1024,104857600], got: " + raw, e);
        }
    }

    private static <T> T bodyJson(Context ctx, Class<T> clazz) {
        try {
            return ctx.bodyAsClass(clazz);
        } catch (Exception e) {
            throw new BadRequestResponse("Invalid JSON body: " + e.getMessage());
        }
    }
}
