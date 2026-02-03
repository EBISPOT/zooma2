package uk.ac.ebi.zooma2;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.plugin.bundled.CorsPluginConfig;
import uk.ac.ebi.zooma2.api.v2.ZoomaApiV2;
import uk.ac.ebi.zooma2.api.v3.ZoomaApiV3;
import uk.ac.ebi.zooma2.embedding.EmbeddingCache;
import uk.ac.ebi.zooma2.embedding.EmbeddingService;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsTermCache;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

public class ZoomaApp {

    public static void main(String[] args) {

        // Initialize embedding components if configured
        EmbeddingService embeddingService = null;
        EmbeddingCache embeddingCache = null;
        
        if (ZoomaConfig.config.embedding != null) {
            try {
                // Initialize SQLite embedding cache
                String dbPath = ZoomaConfig.config.embedding.database_path != null ? 
                               ZoomaConfig.config.embedding.database_path : "embeddings.db";
                embeddingCache = EmbeddingCache.createSqliteCache(dbPath);
                System.err.println("Using SQLite for embedding cache: " + dbPath);
                
                // Initialize embedding service with configured models (or auto-discover all loaded models)
                int batchSize = ZoomaConfig.config.embedding.batch_size != null ? 
                               ZoomaConfig.config.embedding.batch_size : 50;
                List<String> configuredModels = ZoomaConfig.config.embedding.models;
                embeddingService = new EmbeddingService(embeddingCache, batchSize, configuredModels);
                System.err.println("Embedding service initialized");
            } catch (Exception e) {
                System.err.println("Warning: Failed to initialize embedding service: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("Embedding configuration not found, vector search disabled");
        }

        var mappingTablesRepo = new MappingTablesRepo(embeddingService);
        var olsRepo = new OlsClientRepo();

        // Initialize OLS term cache
        OlsTermCache olsTermCache = OlsTermCache.createSqliteCache("ols_cache.db");
        olsRepo.setTermCache(olsTermCache);

        // Warm up OLS term cache with all semantic tags from curated mappings
        warmupOlsTermCache(mappingTablesRepo, olsRepo);

        var annotator = new ZoomaAnnotator(
            mappingTablesRepo,
            olsRepo,
            embeddingService
        );

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

        // Register API routes
        var apiV2 = new ZoomaApiV2(annotator, mappingTablesRepo, olsRepo);
        apiV2.registerRoutes(app);
        
        var apiV3 = new ZoomaApiV3(annotator, mappingTablesRepo, olsRepo);
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

    /**
     * Warm up the OLS term cache by resolving all semantic tags from curated mappings.
     * Runs synchronously before server starts.
     * Skips IRIs that previously failed (but live queries will still try them).
     */
    private static void warmupOlsTermCache(MappingTablesRepo mappingTablesRepo, OlsClientRepo olsRepo) {
        System.err.println("Starting OLS term cache warmup...");
        
        PrefixMap prefixMap = new PrefixMap();
        Set<String> semanticTags = mappingTablesRepo.getAllSemanticTags();
        System.err.println("Found " + semanticTags.size() + " distinct semantic tags in curated mappings");
        
        // Expand short forms to full IRIs, filter out nulls
        Set<String> expandedIris = semanticTags.stream()
            .filter(tag -> tag != null && !tag.isEmpty())
            .map(tag -> prefixMap.shortFormToIri(tag))
            .filter(iri -> iri != null && !iri.isEmpty())
            .collect(Collectors.toSet());
        
        System.err.println("Expanded to " + expandedIris.size() + " distinct IRIs");
        
        // Get previously failed IRIs to skip during warmup
        var termCache = olsRepo.getTermCache();
        Set<String> failedIris = termCache != null ? termCache.getFailedIris() : Set.of();
        Set<String> alreadyCached = termCache != null ? termCache.getTerms(expandedIris).keySet() : Set.of();
        
        // Filter out already cached and previously failed IRIs
        List<String> irisToResolve = expandedIris.stream()
            .filter(iri -> !failedIris.contains(iri))
            .filter(iri -> !alreadyCached.contains(iri))
            .collect(Collectors.toList());
        
        System.err.println("Skipping " + failedIris.size() + " previously failed and " + 
            alreadyCached.size() + " already cached, " + irisToResolve.size() + " to resolve");
        
        if (irisToResolve.isEmpty()) {
            System.err.println("OLS cache warmup complete (nothing to do)");
            return;
        }
        
        // Resolve in batches to avoid overwhelming OLS
        int batchSize = 100;
        int resolved = 0;
        
        for (int i = 0; i < irisToResolve.size(); i += batchSize) {
            int end = Math.min(i + batchSize, irisToResolve.size());
            List<String> batch = irisToResolve.subList(i, end);
            
            var terms = olsRepo.resolveTerms(batch);
            resolved += terms.size();
            
            if ((i / batchSize) % 10 == 0) {
                System.err.println("OLS cache warmup progress: " + (i + batch.size()) + "/" + irisToResolve.size() + 
                    " (" + resolved + " resolved)");
            }
        }
        
        System.err.println("OLS term cache warmup complete: " + resolved + " terms cached");
    }
}
