package uk.ac.ebi.zooma2;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.plugin.bundled.CorsPluginConfig;
import uk.ac.ebi.zooma2.embedding.EmbeddingCache;
import uk.ac.ebi.zooma2.embedding.EmbeddingService;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsOntology;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Zooma2App {

    public static void main(String[] args) {

        // Initialize embedding components if configured
        EmbeddingService embeddingService = null;
        EmbeddingCache embeddingCache = null;
        
        if (Zooma2Config.config.embedding != null) {
            try {
                // Initialize SQLite embedding cache
                String dbPath = Zooma2Config.config.embedding.database_path != null ? 
                               Zooma2Config.config.embedding.database_path : "embeddings.db";
                embeddingCache = EmbeddingCache.createSqliteCache(dbPath);
                System.err.println("Using SQLite for embedding cache: " + dbPath);
                
                // Initialize embedding service (auto-discovers all loaded models)
                int batchSize = Zooma2Config.config.embedding.batch_size != null ? 
                               Zooma2Config.config.embedding.batch_size : 50;
                embeddingService = new EmbeddingService(embeddingCache, batchSize);
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

        var annotator = new Zooma2Annotator(
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

        app.get("/v2/api/sources", ctx -> {

             var databases = Zooma2Config.config.datasources.entrySet().stream()
                .map(entry -> {
                    return Map.of(
                        "type", "DATABASE",
                        "name", entry.getKey(),
                        "uri", entry.getValue().uri
                    );
                });

             var ontologies = olsRepo.getOntologies().stream()
                .map((OlsOntology o) -> Map.of(
                    "type", "ONTOLOGY",
                    "name", o.ontologyId,
                    "title", o.config.title != null ? o.config.title : "",
                    "description", o.config.description != null ? o.config.description : "",
                    "uri", o.ontologyId
                ));

            ctx.json( Stream.concat(databases, ontologies).toList() );
        });

        app.get("/v2/api/properties/types", ctx -> {
            ctx.json(mappingTablesRepo.getAllTypes());
        });

        // GET /services/annotate?propertyValue=...&propertyType=...&filter=...
        app.get("/v2/api/services/annotate", ctx -> {
            String propertyValue = q(ctx, "propertyValue", true);
            String propertyType  = q(ctx, "propertyType", false);
            String filterRaw     = q(ctx, "filter", false);
            Filter filter = Filter.parse(filterRaw);

            ctx.json(annotator.annotate(propertyValue, propertyType, filter));
        });

        app.post("/v2/api/services/map", ctx -> {

            var stringsToMap = bodyJson(ctx, uk.ac.ebi.zooma2.model.StringToMap[].class);

            String filterRaw = q(ctx, "filter", false);
            Filter filter = Filter.parse(filterRaw);

            Collection<MapResult> results = annotator.mapAll(Arrays.stream(stringsToMap), filter);

            ctx.json(results);
        });

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
