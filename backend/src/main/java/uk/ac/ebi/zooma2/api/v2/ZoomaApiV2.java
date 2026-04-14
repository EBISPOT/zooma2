package uk.ac.ebi.zooma2.api.v2;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import uk.ac.ebi.zooma2.ZoomaAnnotator;
import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.api.v2.dto.V2AnnotationDto;
import uk.ac.ebi.zooma2.api.v2.dto.V2FilterDto;
import uk.ac.ebi.zooma2.api.v2.dto.V2MapResultDto;
import uk.ac.ebi.zooma2.api.v2.dto.V2StringToMapDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsOntology;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V2 API endpoints - maintained for backward compatibility.
 * New clients should use V3 API.
 */
public class ZoomaApiV2 {

    private final ZoomaAnnotator annotator;
    private final OlsClientRepo olsRepo;

    /** Session-scoped async mapping jobs, keyed by JSESSIONID cookie value. */
    private final ConcurrentHashMap<String, MapJob> mapJobs = new ConcurrentHashMap<>();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static class MapJob {
        final List<V2StringToMapDto> inputs;
        final String filterRaw;
        volatile double progress;       // 0.0 .. 1.0
        volatile List<V2MapResultDto> results;
        final long createdAt = System.currentTimeMillis();

        MapJob(List<V2StringToMapDto> inputs, String filterRaw) {
            this.inputs = inputs;
            this.filterRaw = filterRaw;
            this.progress = 0.0;
            this.results = null;
        }
    }

    public ZoomaApiV2(ZoomaAnnotator annotator, OlsClientRepo olsRepo) {
        this.annotator = annotator;
        this.olsRepo = olsRepo;
    }

    public void registerRoutes(Javalin app) {
        app.get("/v2/api/sources", this::getSources);
        app.get("/v2/api/properties/types", this::getPropertyTypes);
        app.get("/v2/api/services/annotate", this::annotate);
        app.post("/v2/api/services/map", this::mapSubmit);
        app.get("/v2/api/services/map/status", this::mapStatus);
        app.get("/v2/api/services/map", this::mapResults);
    }

    private void getSources(Context ctx) {
        try {
            var databases = olsRepo.getCurationSources().stream()
                .map(name -> Map.of(
                    "type", "DATABASE",
                    "name", name,
                    "uri", name
                ));

            var ontologies = olsRepo.getOntologies().stream()
                .map((OlsOntology o) -> Map.of(
                    "type", "ONTOLOGY",
                    "name", o.ontologyId,
                    "title", o.config.title != null ? o.config.title : "",
                    "description", o.config.description != null ? o.config.description : "",
                    "uri", o.ontologyId
                ));

            ctx.json(Stream.concat(databases, ontologies).toList());
        } catch (IOException e) {
            throw new InternalServerErrorResponse("Failed to fetch sources: " + e.getMessage());
        }
    }

    private void getPropertyTypes(Context ctx) {
        ctx.json(List.of());
    }

    private void annotate(Context ctx) {
        String propertyValue = q(ctx, "propertyValue", true);
        String propertyType  = q(ctx, "propertyType", false);
        String filterRaw     = q(ctx, "filter", false);

        var filterDto = V2FilterDto.parse(filterRaw);
        var filter = filterDto != null ? filterDto.toFilter() : null;
        var stm = new StringToMap();
        stm.textToMap = propertyValue;
        stm.propertyType = propertyType;

        var dtoResults = mapCompat(stm, filter).stream()
            .map(this::toV2Annotation)
            .map(V2AnnotationDto::from)
            .collect(Collectors.toList());

        ctx.json(dtoResults);
    }

    private void mapSubmit(Context ctx) {
        var v2StringsToMap = bodyJson(ctx, V2StringToMapDto[].class);
        int count = v2StringsToMap.length;

        String filterRaw = q(ctx, "filter", false);
        var job = new MapJob(Arrays.asList(v2StringsToMap), filterRaw);
        String sessionId = generateSessionId();

        mapJobs.put(sessionId, job);
        ctx.cookie("JSESSIONID", sessionId, -1);
        evictOldJobs();

        // Run mapping asynchronously
        Thread.startVirtualThread(() -> {
            try {
                var filterDto = V2FilterDto.parse(job.filterRaw);
                var filter = filterDto != null ? filterDto.toFilter() : null;
                String model = resolveModel();

                List<V2MapResultDto> allResults = new ArrayList<>();
                int total = job.inputs.size();

                for (int i = 0; i < total; i++) {
                    var stm = job.inputs.get(i).toStringToMap();
                    var results = mapCompat(stm, filter, model);
                    for (var r : results) {
                        allResults.add(V2MapResultDto.from(r));
                    }
                    job.progress = (double)(i + 1) / total;
                }

                job.results = allResults;
            } catch (Exception e) {
                job.progress = 1.0;
                job.results = List.of();
                System.err.println("Async map job failed: " + e.getMessage());
            }
        });

        ctx.contentType("text/plain");
        ctx.result("Mapping request of " + count + " properties was successfully received");
    }

    private void mapStatus(Context ctx) {
        String sessionId = ctx.cookie("JSESSIONID");
        MapJob job = sessionId != null ? mapJobs.get(sessionId) : null;
        double progress = job != null ? job.progress : 0.0;
        ctx.contentType("text/plain");
        ctx.result(String.valueOf(progress));
    }

    private void mapResults(Context ctx) {
        String accept = ctx.header("Accept");
        if (accept != null && accept.contains("application/json")) {
            ctx.status(406);
            ctx.result("");
            return;
        }

        String sessionId = ctx.cookie("JSESSIONID");
        MapJob job = sessionId != null ? mapJobs.get(sessionId) : null;

        if (job == null || job.results == null) {
            ctx.contentType("text/plain");
            ctx.result("");
            return;
        }

        var sb = new StringBuilder();
        var now = LocalDateTime.now();
        sb.append("Application Name:\tZOOMA (Automatic Ontology Mapper)\n");
        sb.append("Version:\t2.0\n");
        sb.append("Run at:\t").append(now.format(DateTimeFormatter.ofPattern("HH:mm.ss, dd.MM.yy"))).append("\n");
        sb.append("Run from:\thttp://www.ebi.ac.uk/fgpt/zooma\n");
        sb.append("\n\n");
        sb.append("PROPERTY TYPE\tPROPERTY VALUE\tONTOLOGY TERM LABEL(S)\tONTOLOGY TERM SYNONYM(S)\t");
        sb.append("CONFIDENCE\tONTOLOGY TERM(S)\tONTOLOGY(S)\tSOURCE(S)\tSTUDY");

        for (var r : job.results) {
            sb.append("\n");
            sb.append(r.propertyType != null ? r.propertyType : "").append("\t");
            sb.append(r.propertyValue != null ? r.propertyValue : "").append("\t");
            sb.append(r.ontologyTermLabel != null ? r.ontologyTermLabel : "").append("\t");
            sb.append(r.ontologyTermSynonyms != null ? r.ontologyTermSynonyms : "").append("\t");
            sb.append(titleCase(r.mappingConfidence)).append("\t");
            sb.append(r.ontologyTermID != null ? r.ontologyTermID : "").append("\t");
            sb.append(r.ontologyURI != null ? r.ontologyURI : "").append("\t");
            sb.append(r.datasource != null ? r.datasource : "").append("\t");
            sb.append("[UNKNOWN EXPERIMENTS]");
        }

        ctx.contentType("text/plain");
        ctx.result(sb.toString());

        // Clean up after retrieval
        mapJobs.remove(sessionId);
    }

    // ==================== Helper methods ====================

    private static String generateSessionId() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        var sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** Convert "HIGH" → "High", "GOOD" → "Good", etc. for TSV output. */
    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    /**
     * Use the same candidate generation path as V3/UI so V2 compatibility
     * inherits exact tagger hits and curated matches instead of only the raw
     * lexical/embedding stream.
     */
    private List<MapResult> mapCompat(StringToMap stm, uk.ac.ebi.zooma2.model.Filter filter) {
        return mapCompat(stm, filter, resolveModel());
    }

    private List<MapResult> mapCompat(StringToMap stm, uk.ac.ebi.zooma2.model.Filter filter, String model) {
        return annotator.mapAll(Stream.of(stm), filter, model, null, true, true).stream()
            .filter(r -> r.error == null)
            .sorted(Comparator.comparingDouble((MapResult r) -> r.mappingConfidence).reversed())
            .collect(Collectors.toList());
    }

    private String resolveModel() {
        String model = olsRepo.getDefaultEmbeddingModel();
        if (model == null || model.isBlank()) {
            model = "text-embedding-3-small";
        }
        return model;
    }

    private Annotation toV2Annotation(MapResult result) {
        Annotation wrapper = new Annotation();
        wrapper.annotatedProperty = annotatedProperty(result);
        wrapper.semanticTags = semanticTags(result);
        wrapper.confidence = result.mappingConfidence;
        wrapper.mappingProvenance = result.mappingProvenance;
        wrapper.sourceAnnotation = toRawAnnotation(result);
        return wrapper;
    }

    private Annotation toRawAnnotation(MapResult result) {
        Annotation raw = new Annotation();
        raw.annotatedProperty = annotatedProperty(result);
        raw.semanticTags = semanticTags(result);
        raw.confidence = result.mappingConfidence;
        raw.mappingProvenance = result.mappingProvenance;

        long now = System.currentTimeMillis();
        var provenance = new Annotation.Provenance();
        provenance.source = new Annotation.Source();

        V3MappingProvenanceStepDto firstStep = firstProvenanceStep(result);
        String sourceName = sourceName(result, firstStep);
        provenance.source.type = firstStep != null && "curated".equals(firstStep.method) ? "DATABASE" : "ONTOLOGY";
        provenance.source.name = sourceName;
        provenance.source.uri = sourceName;
        provenance.evidence = firstStep != null && firstStep.matchType != null ? firstStep.matchType : null;
        provenance.generator = "ZOOMA";
        provenance.annotator = sourceName != null ? sourceName : "ZOOMA";
        provenance.generatedDate = String.valueOf(now);
        provenance.annotationDate = String.valueOf(now);
        raw.provenance = provenance;

        return raw;
    }

    private Annotation.AnnotatedProperty annotatedProperty(MapResult result) {
        var property = new Annotation.AnnotatedProperty();
        property.propertyType = result.propertyType;
        property.propertyValue = result.textToMap;
        return property;
    }

    private List<String> semanticTags(MapResult result) {
        String tag = resolveSemanticTag(result);
        return tag == null || tag.isBlank() ? List.of() : List.of(tag);
    }

    private String resolveSemanticTag(MapResult result) {
        if (result.mappingProvenance != null) {
            for (int i = result.mappingProvenance.size() - 1; i >= 0; i--) {
                var step = result.mappingProvenance.get(i);
                if (step != null && step.target != null && !step.target.isBlank()) {
                    return step.target;
                }
            }
        }
        return result.ontologyTermID;
    }

    private V3MappingProvenanceStepDto firstProvenanceStep(MapResult result) {
        if (result.mappingProvenance == null || result.mappingProvenance.isEmpty()) {
            return null;
        }
        return result.mappingProvenance.get(0);
    }

    private String sourceName(MapResult result, V3MappingProvenanceStepDto firstStep) {
        if (result.datasource != null && !result.datasource.isBlank()) {
            return result.datasource;
        }
        if (firstStep == null || firstStep.source == null || firstStep.source.isBlank()) {
            return null;
        }
        return firstStep.source.startsWith("ols:") ? firstStep.source.substring(4) : firstStep.source;
    }

    /** Remove jobs older than 30 minutes to prevent unbounded memory growth. */
    private void evictOldJobs() {
        long cutoff = System.currentTimeMillis() - 30 * 60 * 1000;
        mapJobs.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }
    
    private static String q(Context ctx, String name, boolean required) {
        String v = ctx.queryParam(name);
        if (required && (v == null || v.isBlank())) {
            throw new BadRequestResponse("Missing required query parameter: " + name);
        }
        return v;
    }

    private static <T> T bodyJson(Context ctx, Class<T> clazz) {
        try {
            return ctx.bodyAsClass(clazz);
        } catch (Exception e) {
            throw new BadRequestResponse("Invalid JSON body: " + e.getMessage());
        }
    }
}
