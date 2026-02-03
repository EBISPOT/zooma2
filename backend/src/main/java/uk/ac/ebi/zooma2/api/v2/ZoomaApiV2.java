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
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsOntology;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V2 API endpoints - maintained for backward compatibility.
 * New clients should use V3 API.
 */
public class ZoomaApiV2 {

    private final ZoomaAnnotator annotator;
    private final MappingTablesRepo mappingTablesRepo;
    private final OlsClientRepo olsRepo;

    public ZoomaApiV2(ZoomaAnnotator annotator, MappingTablesRepo mappingTablesRepo, OlsClientRepo olsRepo) {
        this.annotator = annotator;
        this.mappingTablesRepo = mappingTablesRepo;
        this.olsRepo = olsRepo;
    }

    public void registerRoutes(Javalin app) {
        app.get("/v2/api/sources", this::getSources);
        app.get("/v2/api/properties/types", this::getPropertyTypes);
        app.get("/v2/api/services/annotate", this::annotate);
        app.post("/v2/api/services/map", this::map);
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

    private void annotate(Context ctx) {
        String propertyValue = q(ctx, "propertyValue", true);
        String propertyType  = q(ctx, "propertyType", false);
        String filterRaw     = q(ctx, "filter", false);
        
        var filterDto = V2FilterDto.parse(filterRaw);
        var filter = filterDto != null ? filterDto.toFilter() : null;

        var internalResults = annotator.annotate(propertyValue, propertyType, filter).collect(Collectors.toList());
        var dtoResults = internalResults.stream().map(V2AnnotationDto::from).collect(Collectors.toList());
        
        ctx.json(dtoResults);
    }

    private void map(Context ctx) {
        var v2StringsToMap = bodyJson(ctx, V2StringToMapDto[].class);
        var internalStringsToMap = Arrays.stream(v2StringsToMap).map(V2StringToMapDto::toStringToMap);

        String filterRaw = q(ctx, "filter", false);
        var filterDto = V2FilterDto.parse(filterRaw);
        var filter = filterDto != null ? filterDto.toFilter() : null;

        // Always use combined lexical + semantic search
        Collection<uk.ac.ebi.zooma2.model.MapResult> internalResults = annotator.mapAll(
            internalStringsToMap, filter, "text-embedding-3-small", null
        );
        var dtoResults = internalResults.stream().map(V2MapResultDto::from).collect(Collectors.toSet());
        
        ctx.json(dtoResults);
    }

    // ==================== Helper methods ====================
    
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
