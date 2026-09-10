package uk.ac.ebi.zooma2.api.v2;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.router.JavalinDefaultRoutingApi;
import uk.ac.ebi.zooma2.matcher.EvidenceTier;
import uk.ac.ebi.zooma2.ZoomaAnnotator;
import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.api.PropertyTypeMetadata;
import uk.ac.ebi.zooma2.api.RequestLimits;
import uk.ac.ebi.zooma2.api.SourceMetadata;
import uk.ac.ebi.zooma2.api.v2.dto.V2AnnotationDto;
import uk.ac.ebi.zooma2.api.v2.dto.V2FilterDto;
import uk.ac.ebi.zooma2.api.v2.dto.V2MapResultDto;
import uk.ac.ebi.zooma2.api.v2.dto.V2StringToMapDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    static class MapJob {
        final List<V2StringToMapDto> inputs;
        final String filterRaw;
        /** Fraction of properties done; stays below 1.0 until {@link #results} is published. */
        volatile double progress;
        volatile List<V2MapResultDto> results;
        final long createdAt;
        /** Wall-clock time the results were published, 0 while the job is running. */
        volatile long completedAt = 0L;

        MapJob(List<V2StringToMapDto> inputs, String filterRaw) {
            this(inputs, filterRaw, System.currentTimeMillis());
        }

        MapJob(List<V2StringToMapDto> inputs, String filterRaw, long createdAt) {
            this.inputs = inputs;
            this.filterRaw = filterRaw;
            this.createdAt = createdAt;
            this.progress = 0.0;
            this.results = null;
        }

        /** Results first, then completion, so no poller can see progress 1.0 before the results exist. */
        void complete(List<V2MapResultDto> finalResults) {
            this.results = finalResults;
            this.completedAt = System.currentTimeMillis();
            this.progress = 1.0;
        }
    }

    /** A client that has not collected its results within this long is gone. */
    static final long EVICT_COMPLETED_AFTER_MS = 30L * 60 * 1000;
    /** A job still running after this long is stuck; bound memory even so. */
    static final long EVICT_RUNNING_AFTER_MS = 6L * 60 * 60 * 1000;

    /**
     * Jobs are evicted by completion age, not creation age: the old rule removed
     * any job older than 30 minutes, so a long batch was evicted mid-run by an
     * unrelated submit and its owner's polls reset to 0 forever.
     */
    static boolean shouldEvict(MapJob job, long now) {
        if (job.completedAt > 0) {
            return now - job.completedAt > EVICT_COMPLETED_AFTER_MS;
        }
        return now - job.createdAt > EVICT_RUNNING_AFTER_MS;
    }

    public ZoomaApiV2(ZoomaAnnotator annotator, OlsClientRepo olsRepo) {
        this.annotator = annotator;
        this.olsRepo = olsRepo;
    }

    public void registerRoutes(JavalinDefaultRoutingApi app) {
        app.get("/v2/api/sources", this::getSources);
        app.get("/v2/api/properties/types", this::getPropertyTypes);
        app.get("/v2/api/services/annotate", this::annotate);
        app.post("/v2/api/services/map", this::mapSubmit);
        app.get("/v2/api/services/map/status", this::mapStatus);
        app.get("/v2/api/services/map", this::mapResults);
    }

    private void getSources(Context ctx) {
        try {
            ctx.json(SourceMetadata.buildSources(olsRepo.getOntologies()));
        } catch (IOException e) {
            throw new InternalServerErrorResponse("Failed to fetch sources: " + e.getMessage());
        }
    }

    private void getPropertyTypes(Context ctx) {
        ctx.json(PropertyTypeMetadata.legacyPropertyTypes());
    }

    private void annotate(Context ctx) {
        String propertyValue = q(ctx, "propertyValue", true);
        String propertyType  = q(ctx, "propertyType", false);
        String filterRaw     = q(ctx, "filter", false);
        RequestLimits.validateString("propertyValue", propertyValue, true, RequestLimits.MAX_PROPERTY_TEXT_LENGTH);
        RequestLimits.validateString("propertyType", propertyType, false, RequestLimits.MAX_PROPERTY_TYPE_LENGTH);

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
        validateSubmission(v2StringsToMap);
        int count = v2StringsToMap.length;

        String filterRaw = q(ctx, "filter", false);
        // Parse now so a malformed filter is a 400, not a silently empty job
        V2FilterDto.parse(filterRaw);
        var job = new MapJob(Arrays.asList(v2StringsToMap), filterRaw);
        String sessionId = generateSessionId();

        mapJobs.put(sessionId, job);
        ctx.cookie("JSESSIONID", sessionId, -1);
        evictOldJobs();

        // Run the whole batch through the same pipeline as V3: one bulk tag_text
        // call and one virtual thread per property, instead of one full deep
        // mapping (and one bulk-tagger POST) per property in sequence.
        Thread.startVirtualThread(() -> runJob(job));

        ctx.contentType("text/plain");
        ctx.result("Mapping request of " + count + " properties was successfully received");
    }

    private void runJob(MapJob job) {
        try {
            var filterDto = V2FilterDto.parse(job.filterRaw);
            var filter = filterDto != null ? filterDto.toFilter() : null;
            String model = resolveModel();
            int total = job.inputs.size();

            List<StringToMap> properties = new ArrayList<>(total);
            Map<StringToMap, Integer> inputIndex = new IdentityHashMap<>();
            for (int i = 0; i < total; i++) {
                var stm = job.inputs.get(i).toStringToMap();
                properties.add(stm);
                inputIndex.put(stm, i);
            }

            // Properties complete in any order; the report keeps input order.
            List<List<V2MapResultDto>> perInput = new ArrayList<>(Collections.nCopies(total, null));
            var completed = new AtomicInteger();
            // deep=null: escalate to the deep search only when an ontology filter is set
            // and the shallow search misses it, as V3 does, rather than forcing the full
            // deep pipeline for every property of a spreadsheet.
            annotator.mapEach(properties, filter, model, null, true, null, (prop, results) -> {
                List<V2MapResultDto> dtos = results.stream()
                    .filter(r -> !r.isDiagnostic())
                    .sorted(EvidenceTier.resultRanking())
                    .map(V2MapResultDto::from)
                    .collect(Collectors.toList());
                synchronized (perInput) {
                    perInput.set(inputIndex.get(prop), dtos);
                }
                // Never 1.0 here: that is the signal that the results are published
                job.progress = Math.min(0.99, (double) completed.incrementAndGet() / total);
            });

            List<V2MapResultDto> allResults = new ArrayList<>();
            synchronized (perInput) {
                for (var rows : perInput) {
                    if (rows != null) allResults.addAll(rows);
                }
            }
            job.complete(allResults);
        } catch (Exception e) {
            System.err.println("Async map job failed: " + MapResult.describe(e));
            e.printStackTrace();
            job.complete(List.of());
        }
    }

    /** The V3 input caps, applied to a legacy bulk submission. */
    static void validateSubmission(V2StringToMapDto[] rows) {
        if (rows == null || rows.length == 0) {
            throw new BadRequestResponse("Request body must be a non-empty array of properties");
        }
        if (rows.length > RequestLimits.MAX_PROPERTIES) {
            throw new BadRequestResponse("Request cannot contain more than " + RequestLimits.MAX_PROPERTIES + " properties");
        }
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null) {
                throw new BadRequestResponse("properties[" + i + "] cannot be null");
            }
            RequestLimits.validateString("properties[" + i + "].propertyValue", rows[i].propertyValue, true, RequestLimits.MAX_PROPERTY_TEXT_LENGTH);
            RequestLimits.validateString("properties[" + i + "].propertyType", rows[i].propertyType, false, RequestLimits.MAX_PROPERTY_TYPE_LENGTH);
        }
    }

    private void mapStatus(Context ctx) {
        disableCaching(ctx);
        String sessionId = ctx.cookie("JSESSIONID");
        MapJob job = sessionId != null ? mapJobs.get(sessionId) : null;
        // 1.0 means "results are ready to fetch", so it is derived from the results
        // having been published rather than from the running count.
        double progress = job == null ? 0.0 : (job.results != null ? 1.0 : Math.min(job.progress, 0.99));
        ctx.contentType("text/plain");
        ctx.result(String.valueOf(progress));
    }

    private void mapResults(Context ctx) {
        disableCaching(ctx);
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
        sb.append("PROPERTY TYPE\tPROPERTY VALUE\tONTOLOGY TERM LABEL(S)\t");
        sb.append("CONFIDENCE\tONTOLOGY TERM(S)\tONTOLOGY(S)\tSOURCE(S)\tSTUDY");

        for (var r : job.results) {
            sb.append("\n");
            sb.append(r.propertyType != null ? r.propertyType : "").append("\t");
            sb.append(r.propertyValue != null ? r.propertyValue : "").append("\t");
            sb.append(r.ontologyTermLabel != null ? r.ontologyTermLabel : "").append("\t");
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
     * Prevent proxy/browser caching for session-scoped async endpoints.
     * Without this, repeated GET polls to /map/status can get stuck on a
     * cached initial "0.0" even though the job has already completed.
     */
    private static void disableCaching(Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        ctx.header("Pragma", "no-cache");
        ctx.header("Expires", "0");
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
            .filter(r -> !r.isDiagnostic())
            .sorted(EvidenceTier.resultRanking())
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
        provenance.source.uri = provenance.source.type.equals("DATABASE") ? SourceMetadata.sourceUri(sourceName) : sourceName;
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

    /** Remove finished jobs nobody collected and stuck jobs, to prevent unbounded memory growth. */
    private void evictOldJobs() {
        long now = System.currentTimeMillis();
        mapJobs.entrySet().removeIf(e -> shouldEvict(e.getValue(), now));
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
