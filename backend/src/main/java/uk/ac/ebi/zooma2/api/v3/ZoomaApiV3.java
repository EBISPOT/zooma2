package uk.ac.ebi.zooma2.api.v3;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.router.JavalinDefaultRoutingApi;
import com.google.gson.Gson;
import uk.ac.ebi.zooma2.ZoomaAnnotator;
import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.api.PropertyTypeMetadata;
import uk.ac.ebi.zooma2.api.SourceMetadata;
import uk.ac.ebi.zooma2.api.v3.dto.AnnotateTextRequestDto;
import uk.ac.ebi.zooma2.api.v3.dto.TextSegmentDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3FilterDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MapRequestDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MapResponseDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingCandidateDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3PropertyMappingDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3StringToMapDto;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.nlp.TextSegmenter;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.VoteRepository;
import uk.ac.ebi.zooma2.repo.ZoomaDatabase;
import uk.ac.ebi.zooma2.util.RequestCancellation;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V3 API endpoints - current recommended API version.
 * Uses semantic search with embedding models by default.
 */
public class ZoomaApiV3 {

    private static final int MAX_PROPERTIES = envInt("ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000);
    private static final int MAX_DEEP_PROPERTIES = envInt("ZOOMA2_MAX_DEEP_PROPERTIES", 200, 1, 100_000);
    private static final int MAX_PROPERTY_TEXT_LENGTH = envInt("ZOOMA2_MAX_PROPERTY_TEXT_LENGTH", 1000, 1, 1_000_000);
    private static final int MAX_PROPERTY_TYPE_LENGTH = envInt("ZOOMA2_MAX_PROPERTY_TYPE_LENGTH", 200, 1, 10_000);
    private static final int MAX_ANNOTATE_TEXT_LENGTH = envInt("ZOOMA2_MAX_ANNOTATE_TEXT_LENGTH", 50_000, 1, 5_000_000);
    private static final int MAX_LIST_ITEMS = envInt("ZOOMA2_MAX_FILTER_ITEMS", 200, 1, 100_000);
    private static final int MAX_LIST_ITEM_LENGTH = envInt("ZOOMA2_MAX_FILTER_ITEM_LENGTH", 200, 1, 10_000);
    private static final int MAX_EXCLUDED_TERMS = envInt("ZOOMA2_MAX_EXCLUDED_TERMS", 1000, 1, 100_000);
    private static final int MAX_MODEL_LENGTH = envInt("ZOOMA2_MAX_MODEL_LENGTH", 200, 1, 10_000);

    private final ZoomaAnnotator annotator;
    private final OlsClientRepo olsRepo;
    private final VoteRepository voteRepo;
    private final ZoomaDatabase zoomaDb;
    private final TextSegmenter textSegmenter;
    private final Gson gson = new Gson();

    public ZoomaApiV3(ZoomaAnnotator annotator, OlsClientRepo olsRepo, VoteRepository voteRepo, ZoomaDatabase zoomaDb, TextSegmenter textSegmenter) {
        this.annotator = annotator;
        this.olsRepo = olsRepo;
        this.voteRepo = voteRepo;
        this.zoomaDb = zoomaDb;
        this.textSegmenter = textSegmenter;
    }

    public void registerRoutes(JavalinDefaultRoutingApi app) {
        // Core endpoints
        app.get("/v3/api/health", this::getHealth);
        app.get("/v3/api/sources", this::getSources);
        app.get("/v3/api/properties/types", this::getPropertyTypes);
        
        // Embedding model endpoints
        app.get("/v3/api/models", this::getModels);
        
        // Diagnostic endpoint
        app.get("/v3/api/status", this::getStatus);
        
        // Unified mapping endpoint - uses semantic search by default
        app.post("/v3/api/services/map", this::map);
        
        // Streaming mapping endpoint - sends results as NDJSON as each property completes
        app.post("/v3/api/services/map-stream", this::mapStream);

        // Annotate text endpoint - NLP segmentation + streaming mapping
        app.post("/v3/api/services/annotate-text-stream", this::annotateTextStream);

        // Ontology presets
        app.get("/v3/api/ontology-presets", this::getOntologyPresets);

        // Vote endpoints are disabled until anonymous feedback has abuse controls.
        // app.post("/v3/api/votes", this::recordVote);
        // app.get("/v3/api/votes", this::getVotes);
    }

    private void getHealth(Context ctx) {
        ctx.result("All systems are operational.");
    }

    private void getStatus(Context ctx) {
        var status = new java.util.HashMap<String, Object>();
        long vectorIndexSize = zoomaDb.countEmbeddingsBySourceType("mapping_table");
        String defaultModel = olsRepo.getDefaultEmbeddingModel();
        status.put("olsUrl", uk.ac.ebi.zooma2.repo.OlsClientRepo.getOlsUrl());
        status.put("defaultModel", defaultModel);
        status.put("embeddingServiceEnabled", defaultModel != null);
        status.put("totalMappingEntries", zoomaDb.countDistinctEmbeddingTexts());
        status.put("vectorIndexEnabled", vectorIndexSize > 0);
        status.put("vectorIndexSize", vectorIndexSize);
        ctx.json(status);
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

    private void getOntologyPresets(Context ctx) {
        var presets = ZoomaConfig.config.ontology_presets;
        if (presets == null) {
            ctx.json(List.of());
            return;
        }

        boolean needsObo = presets.stream()
                .anyMatch(p -> Boolean.TRUE.equals(p.include_obo_ontologies));

        List<String> oboIds = List.of();
        if (needsObo) {
            try {
                oboIds = olsRepo.getOboOntologyIds();
            } catch (IOException e) {
                System.err.println("Failed to fetch OBO Foundry ontologies: " + e.getMessage());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var preset : presets) {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", preset.name);
            map.put("description", preset.description);

            List<String> ontologies = preset.ontologies != null
                    ? new ArrayList<>(preset.ontologies) : new ArrayList<>();
            if (Boolean.TRUE.equals(preset.include_obo_ontologies)) {
                for (String id : oboIds) {
                    if (!ontologies.contains(id)) {
                        ontologies.add(id);
                    }
                }
            }
            map.put("ontologies", ontologies);
            result.add(map);
        }

        ctx.json(result);
    }

    /**
     * Get available embedding models.
     */
    private void getModels(Context ctx) {
        ctx.json(annotator.getEmbeddingModels());
    }

    /**
     * Unified mapping endpoint using semantic search with embedding models.
     * All parameters are in the JSON request body.
     */
    private void map(Context ctx) {
        var request = bodyJson(ctx, V3MapRequestDto.class);
        validateMapRequest(request);

        var internalStringsToMap = request.properties.stream().map(V3StringToMapDto::toStringToMap);

        var targetOntologies = request.targetOntologies;
        boolean includeOtherOntologies = request.includeOtherOntologies == null || request.includeOtherOntologies;
        boolean limitPerOntology = Boolean.TRUE.equals(request.limitPerOntology);
        var filter = request.filter != null
            ? request.filter.toFilter(targetOntologies, includeOtherOntologies, limitPerOntology)
            : Filter.fromLists(null, null, targetOntologies, includeOtherOntologies, limitPerOntology);
        filter = filter.withRuleSets(request.ruleSets);
        
        // Use requested model, or get default from OLS (first with can_embed=true)
        String model = request.model;
        if (model == null || model.isEmpty()) {
            model = olsRepo.getDefaultEmbeddingModel();
            if (model == null) {
                model = "text-embedding-3-small"; // Fallback
            }
        }
        
        // Get internal results
        boolean returnAll = request.returnAll != null && request.returnAll;
        Collection<MapResult> internalResults = annotator.mapAll(
            internalStringsToMap, filter, model, request.excludeTermIds, returnAll, request.deep
        );
        
        // Group results by input property (normalizing null/unspecified propertyType)
        Map<String, List<MapResult>> groupedResults = internalResults.stream()
            .collect(Collectors.groupingBy(r -> normalizePropertyType(r.propertyType) + "|||" + r.textToMap));
        
        // Build response grouped by input property
        List<V3PropertyMappingDto> mappings = request.properties.stream()
            .map(prop -> {
                String key = normalizePropertyType(prop.propertyType) + "|||" + prop.textToMap;
                List<MapResult> results = groupedResults.getOrDefault(key, List.of());
                
                // Check if any result is an error
                String error = results.stream()
                    .filter(r -> r.error != null)
                    .map(r -> r.error)
                    .findFirst().orElse(null);
                
                List<V3MappingCandidateDto> candidates = results.stream()
                    .filter(r -> r.error == null)
                    .sorted(rankingComparator())
                    .map(V3MappingCandidateDto::from)
                    .collect(Collectors.toList());
                
                var dto = V3PropertyMappingDto.of(prop.propertyType, prop.textToMap, candidates);
                dto.error = error;
                return dto;
            })
            .collect(Collectors.toList());
        
        ctx.json(V3MapResponseDto.of(mappings));
    }

    /**
     * Streaming mapping endpoint. Sends results as NDJSON (one JSON object per line)
     * as each property completes mapping. Each line is a JSON object with:
     * - type: "result" or "done"
     * - mapping: V3PropertyMappingDto (for "result" events)
     * - completed: number of properties mapped so far
     * - total: total number of properties to map
     */
    private void mapStream(Context ctx) {
        var request = bodyJson(ctx, V3MapRequestDto.class);
        validateMapRequest(request);

        var targetOntologies = request.targetOntologies;
        boolean includeOtherOntologies = request.includeOtherOntologies == null || request.includeOtherOntologies;
        boolean limitPerOntology = Boolean.TRUE.equals(request.limitPerOntology);
        var filter = request.filter != null
            ? request.filter.toFilter(targetOntologies, includeOtherOntologies, limitPerOntology)
            : Filter.fromLists(null, null, targetOntologies, includeOtherOntologies, limitPerOntology);
        filter = filter.withRuleSets(request.ruleSets);
        
        String model = request.model;
        if (model == null || model.isEmpty()) {
            model = olsRepo.getDefaultEmbeddingModel();
            if (model == null) {
                model = "text-embedding-3-small";
            }
        }

        boolean returnAll = request.returnAll != null && request.returnAll;
        
        int total = request.properties.size();
        
        List<uk.ac.ebi.zooma2.model.StringToMap> properties = request.properties.stream()
            .map(V3StringToMapDto::toStringToMap)
            .collect(Collectors.toList());
        
        ctx.res().setContentType("application/x-ndjson");
        ctx.res().setCharacterEncoding("UTF-8");

        // Create cancellation flag before mapEach so the heartbeat and mapEach share
        // the same AtomicBoolean instance (newCancellationFlag reuses if already set).
        var cancelled = RequestCancellation.newFlag();
        try {
            var out = ctx.res().getOutputStream();
            var completed = new java.util.concurrent.atomic.AtomicInteger(0);
            var mappingDone = new java.util.concurrent.atomic.AtomicBoolean(false);

            // Heartbeat: write a ping line every 200 ms so we detect a closed
            // connection immediately. Without this, disconnect is only noticed
            // when a result write fails — which may never happen if the OS
            // socket buffer absorbs all remaining data.
            var heartbeatThread = Thread.ofVirtual().start(() -> {
                while (!mappingDone.get()) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (mappingDone.get()) return;
                    try {
                        synchronized (out) {
                            out.write("{\"type\":\"ping\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException e) {
                        cancelled.set(true);
                        System.err.println("Client disconnected (heartbeat): " + e.getMessage());
                        return;
                    }
                }
            });

            try {
                annotator.mapEach(properties, filter, model, request.excludeTermIds, returnAll, request.deep, (prop, results) -> {
                    // Abort immediately if heartbeat (or a prior write) already detected disconnect.
                    if (cancelled.get()) {
                        throw new java.io.UncheckedIOException(new IOException("Client disconnected"));
                    }

                    // Check if any result is an error
                    String error = results.stream()
                        .filter(r -> r.error != null)
                        .map(r -> r.error)
                        .findFirst().orElse(null);

                    List<V3MappingCandidateDto> candidates = results.stream()
                        .filter(r -> r.error == null)
                        .sorted(rankingComparator())
                        .map(V3MappingCandidateDto::from)
                        .collect(Collectors.toList());

                    var mapping = V3PropertyMappingDto.of(prop.propertyType, prop.textToMap, candidates);
                    mapping.error = error;
                    int done = completed.incrementAndGet();

                    var event = new LinkedHashMap<String, Object>();
                    event.put("type", "result");
                    event.put("mapping", mapping);
                    event.put("completed", done);
                    event.put("total", total);

                    try {
                        synchronized (out) {
                            out.write((gson.toJson(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });

                if (!cancelled.get()) {
                    var doneEvent = new LinkedHashMap<String, Object>();
                    doneEvent.put("type", "done");
                    doneEvent.put("completed", total);
                    doneEvent.put("total", total);
                    out.write((gson.toJson(doneEvent) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                }
            } finally {
                mappingDone.set(true);
                heartbeatThread.interrupt();
            }

        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("Client disconnected during streaming, stopping mapping (" + e.getMessage() + ")");
        } finally {
            RequestCancellation.clearFlag();
        }
    }

    /**
     * Annotate free text: run OLS tag_text on the whole text as a fast initial pass,
     * then segment using NLP, merge segments, and stream mapping results.
     * First message is {"type":"segments",...} with extracted/tagged phrases and their offsets.
     * Subsequent messages are {"type":"result",...} as each unique segment completes mapping.
     * Final message is {"type":"done",...}.
     */
    private void annotateTextStream(Context ctx) {
        var request = bodyJson(ctx, AnnotateTextRequestDto.class);
        validateAnnotateTextRequest(request);

        var targetOntologies = request.targetOntologies;
        boolean includeOtherOntologies = request.includeOtherOntologies == null || request.includeOtherOntologies;
        var filter = request.filter != null
            ? request.filter.toFilter(targetOntologies, includeOtherOntologies)
            : Filter.fromLists(null, null, targetOntologies, includeOtherOntologies);
        filter = filter.withRuleSets(request.ruleSets);

        String model = request.model;
        if (model == null || model.isEmpty()) {
            model = olsRepo.getDefaultEmbeddingModel();
            if (model == null) {
                model = "text-embedding-3-small";
            }
        }

        // Step 1: Run OLS tag_text on the whole text AND NLP segmentation in parallel
        var wholeTextMatches = new java.util.concurrent.atomic.AtomicReference<List<OlsClientRepo.WholeTextTagMatch>>();
        var nlpResult = new java.util.concurrent.atomic.AtomicReference<TextSegmenter.SegmentationResult>();

        var tagTextThread = Thread.ofVirtual().start(() ->
            wholeTextMatches.set(annotator.tagWholeText(request.text, targetOntologies))
        );
        var nlpThread = Thread.ofVirtual().start(() ->
            nlpResult.set(textSegmenter.segment(request.text))
        );

        try {
            tagTextThread.join();
            nlpThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalServerErrorResponse("Interrupted during text analysis");
        }

        var tagMatches = wholeTextMatches.get();
        var segResult = nlpResult.get();

        System.err.println("Whole-text tag_text found " + tagMatches.size() + " matches; NLP found " + segResult.segments().size() + " segments");

        // Step 2: Convert tag_text matches to segments and merge with NLP segments
        List<TextSegmenter.TextSegment> tagTextSegments = tagMatches.stream()
            .map(m -> new TextSegmenter.TextSegment(m.matchedText, m.start, m.end))
            .collect(Collectors.toList());

        var mergedResult = TextSegmenter.mergeSegments(segResult.segments(), tagTextSegments);

        int total = mergedResult.uniqueTexts().size();

        // Build StringToMap list from merged unique texts
        List<StringToMap> properties = mergedResult.uniqueTexts().stream()
            .map(text -> {
                var stm = new StringToMap();
                stm.textToMap = text;
                return stm;
            })
            .collect(Collectors.toList());

        // Build segment DTOs for the first message
        List<TextSegmentDto> segmentDtos = mergedResult.segments().stream()
            .map(seg -> new TextSegmentDto(seg.text(), seg.start(), seg.end()))
            .collect(Collectors.toList());

        ctx.res().setContentType("application/x-ndjson");
        ctx.res().setCharacterEncoding("UTF-8");

        var cancelled = RequestCancellation.newFlag();
        try {
            var out = ctx.res().getOutputStream();

            // Step 3: Immediately send segments message
            var segmentsEvent = new LinkedHashMap<String, Object>();
            segmentsEvent.put("type", "segments");
            segmentsEvent.put("segments", segmentDtos);
            segmentsEvent.put("originalText", request.text);
            synchronized (out) {
                out.write((gson.toJson(segmentsEvent) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }

            if (total == 0) {
                // No segments found, send done immediately
                var doneEvent = new LinkedHashMap<String, Object>();
                doneEvent.put("type", "done");
                doneEvent.put("completed", 0);
                doneEvent.put("total", 0);
                synchronized (out) {
                    out.write((gson.toJson(doneEvent) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                }
                return;
            }

            // Step 4: Map each unique segment through the mapping pipeline
            var completed = new java.util.concurrent.atomic.AtomicInteger(0);
            var mappingDone = new java.util.concurrent.atomic.AtomicBoolean(false);

            // Heartbeat thread (same pattern as mapStream)
            var heartbeatThread = Thread.ofVirtual().start(() -> {
                while (!mappingDone.get()) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (mappingDone.get()) return;
                    try {
                        synchronized (out) {
                            out.write("{\"type\":\"ping\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException e) {
                        cancelled.set(true);
                        System.err.println("Client disconnected (heartbeat): " + e.getMessage());
                        return;
                    }
                }
            });

            try {
                annotator.mapEach(properties, filter, model, (prop, results) -> {
                    if (cancelled.get()) {
                        throw new java.io.UncheckedIOException(new IOException("Client disconnected"));
                    }

                    String error = results.stream()
                        .filter(r -> r.error != null)
                        .map(r -> r.error)
                        .findFirst().orElse(null);

                    List<V3MappingCandidateDto> candidates = results.stream()
                        .filter(r -> r.error == null)
                        .sorted(rankingComparator())
                        .map(V3MappingCandidateDto::from)
                        .collect(Collectors.toList());

                    var mapping = V3PropertyMappingDto.of(prop.propertyType, prop.textToMap, candidates);
                    mapping.error = error;
                    int done = completed.incrementAndGet();

                    var event = new LinkedHashMap<String, Object>();
                    event.put("type", "result");
                    event.put("mapping", mapping);
                    event.put("completed", done);
                    event.put("total", total);

                    try {
                        synchronized (out) {
                            out.write((gson.toJson(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });

                if (!cancelled.get()) {
                    var doneEvent = new LinkedHashMap<String, Object>();
                    doneEvent.put("type", "done");
                    doneEvent.put("completed", total);
                    doneEvent.put("total", total);
                    synchronized (out) {
                        out.write((gson.toJson(doneEvent) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } finally {
                mappingDone.set(true);
                heartbeatThread.interrupt();
            }

        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("Client disconnected during annotate-text streaming (" + e.getMessage() + ")");
        } finally {
            RequestCancellation.clearFlag();
        }
    }

    // ==================== Vote endpoints ====================

    private static class VoteRequest {
        public String textToMap;
        public String propertyType;
        public String termId;
        public String termLabel;
        public String ontology;
        public String vote; // "up" or "down"
    }

    private void recordVote(Context ctx) {
        ctx.status(204);
    }

    private void getVotes(Context ctx) {
        ctx.json(List.of());
    }

    // ==================== Helper methods ====================
    
    private static String normalizePropertyType(String propertyType) {
        if (propertyType == null || propertyType.isEmpty() || propertyType.equals("unspecified")) {
            return "";
        }
        return propertyType;
    }

    /**
     * Order candidates by composite {@code rankingScore} (RRF across matchers
     * plus ontology-priority bonus) descending, with raw {@code mappingConfidence}
     * as tiebreaker. Falls back to confidence-only sort when the ranking step
     * hasn't run (e.g. pre-resolved error placeholders).
     */
    private static Comparator<MapResult> rankingComparator() {
        return Comparator
            .comparingDouble((MapResult r) -> r.rankingScore).reversed()
            .thenComparing(Comparator.comparingDouble((MapResult r) -> r.mappingConfidence).reversed());
    }

    private static void validateMapRequest(V3MapRequestDto request) {
        if (request == null) {
            throw new BadRequestResponse("Request body is required");
        }
        if (request.properties == null || request.properties.isEmpty()) {
            throw new BadRequestResponse("'properties' is required and cannot be empty");
        }
        if (request.properties.size() > MAX_PROPERTIES) {
            throw new BadRequestResponse("'properties' cannot contain more than " + MAX_PROPERTIES + " items");
        }
        if (Boolean.TRUE.equals(request.deep) && request.properties.size() > MAX_DEEP_PROPERTIES) {
            throw new BadRequestResponse("'deep' requests cannot contain more than " + MAX_DEEP_PROPERTIES + " properties");
        }
        validateString("model", request.model, false, MAX_MODEL_LENGTH);
        validateStringList("targetOntologies", request.targetOntologies, MAX_LIST_ITEMS, MAX_LIST_ITEM_LENGTH);
        validateStringList("excludeTermIds", request.excludeTermIds, MAX_EXCLUDED_TERMS, MAX_LIST_ITEM_LENGTH);
        validateStringList("ruleSets", request.ruleSets, MAX_LIST_ITEMS, MAX_LIST_ITEM_LENGTH);
        validateFilter(request.filter);

        for (int i = 0; i < request.properties.size(); i++) {
            var property = request.properties.get(i);
            if (property == null) {
                throw new BadRequestResponse("'properties[" + i + "]' cannot be null");
            }
            validateString("properties[" + i + "].textToMap", property.textToMap, true, MAX_PROPERTY_TEXT_LENGTH);
            validateString("properties[" + i + "].propertyType", property.propertyType, false, MAX_PROPERTY_TYPE_LENGTH);
        }
    }

    private static void validateAnnotateTextRequest(AnnotateTextRequestDto request) {
        if (request == null) {
            throw new BadRequestResponse("Request body is required");
        }
        validateString("text", request.text, true, MAX_ANNOTATE_TEXT_LENGTH);
        validateString("model", request.model, false, MAX_MODEL_LENGTH);
        validateStringList("targetOntologies", request.targetOntologies, MAX_LIST_ITEMS, MAX_LIST_ITEM_LENGTH);
        validateStringList("ruleSets", request.ruleSets, MAX_LIST_ITEMS, MAX_LIST_ITEM_LENGTH);
        validateFilter(request.filter);
    }

    private static void validateFilter(V3FilterDto filter) {
        if (filter == null) {
            return;
        }
        validateStringList("filter.required", filter.required, MAX_LIST_ITEMS, MAX_LIST_ITEM_LENGTH);
        validateStringList("filter.preferred", filter.preferred, MAX_LIST_ITEMS, MAX_LIST_ITEM_LENGTH);
    }

    private static void validateStringList(String name, List<String> values, int maxItems, int maxLength) {
        if (values == null) {
            return;
        }
        if (values.size() > maxItems) {
            throw new BadRequestResponse("'" + name + "' cannot contain more than " + maxItems + " items");
        }
        for (int i = 0; i < values.size(); i++) {
            validateString(name + "[" + i + "]", values.get(i), true, maxLength);
        }
    }

    private static void validateString(String name, String value, boolean required, int maxLength) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new BadRequestResponse("'" + name + "' is required and cannot be empty");
            }
            return;
        }
        if (value.length() > maxLength) {
            throw new BadRequestResponse("'" + name + "' cannot be longer than " + maxLength + " characters");
        }
    }

    private static int envInt(String name, int defaultValue, int min, int max) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < min || parsed > max) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid " + name + "='" + raw + "'; using " + defaultValue);
            return defaultValue;
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
