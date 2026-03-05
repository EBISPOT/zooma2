package uk.ac.ebi.zooma2.api.v3;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import com.google.gson.Gson;
import uk.ac.ebi.zooma2.ZoomaAnnotator;
import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.api.v3.dto.V3MapRequestDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MapResponseDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingCandidateDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3PropertyMappingDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3StringToMapDto;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsOntology;
import uk.ac.ebi.zooma2.repo.VoteRepository;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V3 API endpoints - current recommended API version.
 * Uses semantic search with embedding models by default.
 */
public class ZoomaApiV3 {

    private final ZoomaAnnotator annotator;
    private final MappingTablesRepo mappingTablesRepo;
    private final OlsClientRepo olsRepo;
    private final VoteRepository voteRepo;
    private final Gson gson = new Gson();

    public ZoomaApiV3(ZoomaAnnotator annotator, MappingTablesRepo mappingTablesRepo, OlsClientRepo olsRepo, VoteRepository voteRepo) {
        this.annotator = annotator;
        this.mappingTablesRepo = mappingTablesRepo;
        this.olsRepo = olsRepo;
        this.voteRepo = voteRepo;
    }

    public void registerRoutes(Javalin app) {
        // Core endpoints
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

        // Vote endpoints
        app.post("/v3/api/votes", this::recordVote);
        app.get("/v3/api/votes", this::getVotes);
    }

    private void getStatus(Context ctx) {
        var status = new java.util.HashMap<String, Object>();
        status.put("vectorIndexEnabled", mappingTablesRepo.isVectorIndexEnabled());
        status.put("vectorIndexSize", mappingTablesRepo.getVectorIndexSize());
        status.put("totalMappingEntries", mappingTablesRepo.getTotalMappingEntries());
        status.put("embeddingServiceEnabled", annotator.isEmbeddingServiceEnabled());
        status.put("olsUrl", uk.ac.ebi.zooma2.repo.OlsClientRepo.OLS_URL);
        status.put("defaultModel", olsRepo.getDefaultEmbeddingModel());
        ctx.json(status);
    }

    private void getSources(Context ctx) {
        try {
            var databases = ZoomaConfig.config.datasources.entrySet().stream()
                .map(entry -> Map.of(
                    "type", "DATABASE",
                    "name", entry.getKey(),
                    "uri", entry.getValue().uri
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
            throw new InternalServerErrorResponse("Failed to fetch ontologies: " + e.getMessage());
        }
    }

    private void getPropertyTypes(Context ctx) {
        ctx.json(mappingTablesRepo.getAllTypes());
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
        
        if (request.properties == null || request.properties.isEmpty()) {
            throw new BadRequestResponse("'properties' is required and cannot be empty");
        }
        
        var internalStringsToMap = request.properties.stream().map(V3StringToMapDto::toStringToMap);
        
        var filter = request.filter != null ? request.filter.toFilter() : null;
        var preferredOntologies = request.preferredOntologies;
        
        // Use requested model, or get default from OLS (first with can_embed=true)
        String model = request.model;
        if (model == null || model.isEmpty()) {
            model = olsRepo.getDefaultEmbeddingModel();
            if (model == null) {
                model = "text-embedding-3-small"; // Fallback
            }
        }
        
        // Get internal results
        Collection<MapResult> internalResults = annotator.mapAll(
            internalStringsToMap, filter, model, preferredOntologies, request.excludeTermIds
        );
        
        // Group results by input property (normalizing null/unspecified propertyType)
        Map<String, List<MapResult>> groupedResults = internalResults.stream()
            .collect(Collectors.groupingBy(r -> normalizePropertyType(r.propertyType) + "|||" + r.propertyValue));
        
        // Build response grouped by input property
        List<V3PropertyMappingDto> mappings = request.properties.stream()
            .map(prop -> {
                String key = normalizePropertyType(prop.propertyType) + "|||" + prop.propertyValue;
                List<MapResult> results = groupedResults.getOrDefault(key, List.of());
                
                List<V3MappingCandidateDto> candidates = results.stream()
                    .map(V3MappingCandidateDto::from)
                    .sorted(Comparator.comparing(
                        c -> c.confidence != null ? c.confidence : 0.0, 
                        Comparator.reverseOrder()
                    ))
                    .collect(Collectors.toList());
                
                return V3PropertyMappingDto.of(prop.propertyType, prop.propertyValue, candidates);
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
        
        if (request.properties == null || request.properties.isEmpty()) {
            throw new BadRequestResponse("'properties' is required and cannot be empty");
        }
        
        var filter = request.filter != null ? request.filter.toFilter() : null;
        var preferredOntologies = request.preferredOntologies;
        
        String model = request.model;
        if (model == null || model.isEmpty()) {
            model = olsRepo.getDefaultEmbeddingModel();
            if (model == null) {
                model = "text-embedding-3-small";
            }
        }
        
        int total = request.properties.size();
        
        List<uk.ac.ebi.zooma2.model.StringToMap> properties = request.properties.stream()
            .map(V3StringToMapDto::toStringToMap)
            .collect(Collectors.toList());
        
        ctx.res().setContentType("application/x-ndjson");
        ctx.res().setCharacterEncoding("UTF-8");
        
        try {
            var out = ctx.res().getOutputStream();
            int[] completed = {0};
            
            annotator.mapEach(properties, filter, model, preferredOntologies, (prop, results) -> {
                List<V3MappingCandidateDto> candidates = results.stream()
                    .map(V3MappingCandidateDto::from)
                    .sorted(Comparator.comparing(
                        c -> c.confidence != null ? c.confidence : 0.0,
                        Comparator.reverseOrder()
                    ))
                    .collect(Collectors.toList());
                
                var mapping = V3PropertyMappingDto.of(prop.propertyType, prop.propertyValue, candidates);
                completed[0]++;
                
                var event = new LinkedHashMap<String, Object>();
                event.put("type", "result");
                event.put("mapping", mapping);
                event.put("completed", completed[0]);
                event.put("total", total);
                
                try {
                    out.write((gson.toJson(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            
            var doneEvent = new LinkedHashMap<String, Object>();
            doneEvent.put("type", "done");
            doneEvent.put("completed", total);
            doneEvent.put("total", total);
            out.write((gson.toJson(doneEvent) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            
        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("Client disconnected during streaming, stopping mapping (" + e.getMessage() + ")");
        }
    }

    // ==================== Vote endpoints ====================

    private static class VoteRequest {
        public String propertyValue;
        public String propertyType;
        public String termId;
        public String termLabel;
        public String ontology;
        public String vote; // "up" or "down"
    }

    private void recordVote(Context ctx) {
        var req = bodyJson(ctx, VoteRequest.class);
        if (req.propertyValue == null || req.termId == null || req.vote == null) {
            throw new BadRequestResponse("propertyValue, termId, and vote are required");
        }
        if (!"up".equals(req.vote) && !"down".equals(req.vote)) {
            throw new BadRequestResponse("vote must be 'up' or 'down'");
        }
        voteRepo.recordVote(req.propertyValue, req.propertyType, req.termId,
                            req.termLabel, req.ontology, req.vote);
        ctx.json(Map.of("status", "ok"));
    }

    private void getVotes(Context ctx) {
        String propertyValue = ctx.queryParam("propertyValue");
        if (propertyValue == null || propertyValue.isEmpty()) {
            throw new BadRequestResponse("propertyValue query parameter is required");
        }
        ctx.json(voteRepo.getVotes(propertyValue));
    }

    // ==================== Helper methods ====================
    
    private static String normalizePropertyType(String propertyType) {
        if (propertyType == null || propertyType.isEmpty() || propertyType.equals("unspecified")) {
            return "";
        }
        return propertyType;
    }
    
    private static <T> T bodyJson(Context ctx, Class<T> clazz) {
        try {
            return ctx.bodyAsClass(clazz);
        } catch (Exception e) {
            throw new BadRequestResponse("Invalid JSON body: " + e.getMessage());
        }
    }
}
