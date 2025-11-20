package uk.ac.ebi.zooma2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.ac.ebi.zooma2.embedding.EmbeddingService;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.MappingTableEntry;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

public class Zooma2Annotator {

    MappingTablesRepo mappingTablesRepo;
    OlsClientRepo olsRepo;
    EmbeddingService embeddingService;
    PrefixMap prefixMap = new PrefixMap();

    public Zooma2Annotator(
        MappingTablesRepo mappingTablesRepo,
        OlsClientRepo olsRepo
    ) {
        this.mappingTablesRepo = mappingTablesRepo;
        this.olsRepo = olsRepo;
        this.embeddingService = null;
    }

    public Zooma2Annotator(
        MappingTablesRepo mappingTablesRepo,
        OlsClientRepo olsRepo,
        EmbeddingService embeddingService
    ) {
        this.mappingTablesRepo = mappingTablesRepo;
        this.olsRepo = olsRepo;
        this.embeddingService = embeddingService;
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources) {

        var results = stringsToMap
            .parallel()
            .flatMap(s -> map(s.propertyValue, s.propertyType, sources))
            .collect(Collectors.toSet());
        
        return results;
    }

    public Stream<MapResult> map(String stringToMap, String type, Filter sources) {

        var annotated = annotate(stringToMap, type, sources).collect(Collectors.toList());

        var termIrisToResolve = annotated
            .stream()
            .flatMap(a -> a.semanticTags.stream())
            .collect(Collectors.toSet());

        var termMap = olsRepo.resolveTerms(termIrisToResolve);

        return annotated.stream().map(a -> {

            var semanticTag = a.semanticTags.size() > 0 ? a.semanticTags.get(0) : null;

            MapResult r = new MapResult();
            r.propertyType = a.annotatedProperty.propertyType;
            r.propertyValue = a.annotatedProperty.propertyValue;
            if(semanticTag != null && termMap.containsKey(semanticTag)) {
                var term = termMap.get(semanticTag);
                r.ontologyTermID = term.short_form;
                r.ontologyTermLabel = term.label;
                r.ontologyTermSynonyms = term.synonyms != null ? String.join("|", term.synonyms) : null;
                r.ontologyURI = term.ontology_name;
            } else {
                r.ontologyTermLabel = r.propertyValue;
            }
            if(r.ontologyTermID == null) {
                r.ontologyTermID = prefixMap.iriToShortForm(semanticTag);
            }
            if(r.ontologyTermID == null) {
                r.ontologyTermID = semanticTag;
            }
            r.mappingConfidence = a.confidence;
            r.datasource = a.provenance != null && a.provenance.source != null ? a.provenance.source.name : null;
            return r;
        });
        
    }

    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources) {

        var curatedMappings = getMappingsFromTables(stringToMap, type, sources).map(m -> {

            Annotation a = new Annotation();

            a.annotatedProperty = new Annotation.AnnotatedProperty();
            a.annotatedProperty.propertyType = m.propertyType;
            a.annotatedProperty.propertyValue = m.propertyValue;

            a.semanticTags = List.of(m.semanticTag);
            a.confidence = "HIGH";

            a.provenance = new Annotation.Provenance();
            a.provenance.source = new Annotation.Source();
            a.provenance.source.type = "DATABASE";
            a.provenance.source.name = m.databaseId;
            a.provenance.source.uri = m.databaseUrl;
            a.provenance.evidence = "ZOOMA_INFERRED_FROM_CURATED";
            a.provenance.accuracy = "NOT_SPECIFIED";
            a.provenance.generator = "ZOOMA";
            a.provenance.generatedDate = new Date().toString();
            a.provenance.annotator = m.annotator;
            a.provenance.annotationDate = m.annotationDate;

            return a;
        });

        var ontologyMappings = getMatchingTermsFromOls(stringToMap, type, sources).map(t -> {

            Annotation a = new Annotation();

            a.annotatedProperty = new Annotation.AnnotatedProperty();
            a.annotatedProperty.propertyType = type != null ? type : "unspecified";
            a.annotatedProperty.propertyValue = stringToMap;

            a.semanticTags = List.of(t.iri);
            a.confidence = "HIGH";

            a.provenance = new Annotation.Provenance();
            a.provenance.source = new Annotation.Source();
            a.provenance.source.type = "ONTOLOGY";
            a.provenance.source.name = t.ontology_name;
            a.provenance.source.uri = t.ontology_name;
            a.provenance.evidence = "NOT_SPECIFIED";
            a.provenance.accuracy = "NOT_SPECIFIED";
            a.provenance.generator = "ZOOMA";
            a.provenance.generatedDate = new Date().toString();
            a.provenance.annotator = null;
            a.provenance.annotationDate = null;

            return a;
        });

        return Stream.concat(curatedMappings, ontologyMappings);
    }

    private Stream<MappingTableEntry> getMappingsFromTables(String stringToMap, String type, Filter sources) {
        if(sources == null || !isNone(sources.required)) {
            var mappings = mappingTablesRepo.allMappingsForString(stringToMap);
            mappings = filterMappings(mappings, type, sources);
            return mappings;
        } else {
            return Stream.<MappingTableEntry>empty();
        }
    }

    private Stream<OlsTerm> getMatchingTermsFromOls(String stringToMap, String type, Filter sources) {
        if(sources == null || !isNone(sources.ontologies)) {
            // Try exact label match first
            var exactMatches = olsRepo.findByLabelAndOntologies(stringToMap, sources.ontologies);
            
            if (!exactMatches.isEmpty()) {
                return exactMatches.stream();
            }
            
            // Fallback to vector search if enabled and no exact matches
            if (embeddingService != null) {
                System.err.println("No exact OLS matches for '" + stringToMap + "', trying vector search...");
                try {
                    float[] queryEmbedding = embeddingService.getEmbedding(stringToMap, "user_search");
                    if (queryEmbedding != null) {
                        var vectorMatches = olsRepo.findByEmbedding(queryEmbedding, sources.ontologies, 10);
                        System.err.println("Vector search found " + vectorMatches.size() + " OLS matches");
                        return vectorMatches.stream();
                    }
                } catch (Exception e) {
                    System.err.println("Error during OLS vector search: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            return exactMatches.stream();
        } else {
            return Stream.<OlsTerm>empty();
        }
    }


    Stream<MappingTableEntry> filterMappings(Stream<MappingTableEntry> mappings, String type, Filter filter) {

        return mappings
            .filter(a -> type == null || a.propertyType == null || a.propertyType.equals(type))
            .filter(a -> {
                
                if(filter == null) return true;

                if(filter.required.size() > 0) {
                    if(!filter.required.contains(a.databaseId)) {
                        return false;
                    }
                }

                return true;
            });

    }

    boolean isNone(List<String> list) {
        return list != null &&
            list.size() == 1 &&
            (list.get(0).equals("none") || list.get(0).equals("Select None"));
    }

    
}
