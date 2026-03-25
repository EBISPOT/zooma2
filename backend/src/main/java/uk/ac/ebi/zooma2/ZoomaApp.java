package uk.ac.ebi.zooma2;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.plugin.bundled.CorsPluginConfig;
import uk.ac.ebi.zooma2.api.v2.ZoomaApiV2;
import uk.ac.ebi.zooma2.api.v3.ZoomaApiV3;
import uk.ac.ebi.zooma2.repo.ExternalApiCache;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsTermCache;
import uk.ac.ebi.zooma2.repo.VoteRepository;
import uk.ac.ebi.zooma2.repo.ZoomaDatabase;
import uk.ac.ebi.zooma2.util.CachedHttpClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

public class ZoomaApp {

    public static void main(String[] args) {

        // Initialize unified database
        var zoomaDb = new ZoomaDatabase();

        // Initialize external API cache for transparent HTTP caching
        var apiCache = new ExternalApiCache(zoomaDb);
        CachedHttpClient.setApiCache(apiCache);

        var olsEmbeddingCfg = ZoomaConfig.config.ols_embedding;
        int maxConcurrentEmbedding = (olsEmbeddingCfg != null && olsEmbeddingCfg.max_concurrent_embedding_requests != null) 
            ? olsEmbeddingCfg.max_concurrent_embedding_requests : 3;
        int maxConcurrentSimilar = (olsEmbeddingCfg != null && olsEmbeddingCfg.max_concurrent_similar_requests != null) 
            ? olsEmbeddingCfg.max_concurrent_similar_requests : 10;
        var olsRepo = new OlsClientRepo(maxConcurrentEmbedding, maxConcurrentSimilar);

        // Initialize OLS term cache using unified database
        OlsTermCache olsTermCache = new OlsTermCache(zoomaDb);
        olsRepo.setTermCache(olsTermCache);

        var annotator = new ZoomaAnnotator(olsRepo);

        var app = Javalin.create(config -> {
            config.http.generateEtags = true;
            config.router.apiBuilder(() -> {});
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(CorsPluginConfig.CorsRule::anyHost);
            });
            config.router.contextPath = System.getenv("ZOOMA2_CONTEXT_PATH");
            if(config.router.contextPath == null) {
                config.router.contextPath = "";
            }
        });

        // Watch config.json for changes and reload automatically
        ZoomaConfig.startConfigWatcher();

        // Register API routes
        var apiV2 = new ZoomaApiV2(annotator, olsRepo);
        apiV2.registerRoutes(app);
        
        var apiV3 = new ZoomaApiV3(annotator, olsRepo, new VoteRepository(zoomaDb));
        apiV3.registerRoutes(app);

        // Global handlers
        app.exception(BadRequestResponse.class, (e, ctx) -> ctx.status(400).json(Map.of(
            "error", "Bad Request",
            "message", e.getMessage()
        )));
        app.exception(NotFoundResponse.class, (e, ctx) -> ctx.status(404).json(Map.of(
            "error", "Not Found",
            "message", e.getMessage()
        )));
        app.exception(Exception.class, (e, ctx) -> {

            // send the full exception stack trace to the client

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();
            ctx.status(500).json(Map.of(
                "error", "Internal Server Error",
                "message", e.getMessage(),
                "stackTrace", stackTrace
            ));
        });

        app.start(8090);
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

    private static <T> T bodyJson(Context ctx, Class<T> clazz) {
        try {
            return ctx.bodyAsClass(clazz);
        } catch (Exception e) {
            throw new BadRequestResponse("Invalid JSON body: " + e.getMessage());
        }
    }
}
