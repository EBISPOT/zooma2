package uk.ac.ebi.zooma2;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.MappingTableEntry;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

public class Zooma2Annotator {

    MappingTablesRepo mappingTablesRepo;
    OlsClientRepo olsRepo;
    PrefixMap prefixMap = new PrefixMap();

    public Zooma2Annotator(
        MappingTablesRepo mappingTablesRepo,
        OlsClientRepo olsRepo
    ) {
        this.mappingTablesRepo = mappingTablesRepo;
        this.olsRepo = olsRepo;
    }


    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources) {

        var curatedMappings = List.<MappingTableEntry>of().stream();
        var ontologyMappings = List.<OlsTerm>of().stream();

        // 1. Curated mappings from tables, only if "required" is not [none]

        if(sources == null || !isNone(sources.required)) {
            curatedMappings = mappingTablesRepo.allMappingsForString(stringToMap);
            curatedMappings = filterMappings(curatedMappings, type, sources);
        }

        // 2. Mappings from ontologies, only if "ontologies" is not [none]

        if(sources == null || !isNone(sources.ontologies)) {
            ontologyMappings = olsRepo.findByLabelAndOntologies(stringToMap, sources.ontologies).stream();
        }

        return Stream.concat( curatedMappings.map(m -> {

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
        }),

        ontologyMappings.map(t -> {

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
        }));
    }

    public Stream<MapResult> map(String stringToMap, String type, Filter sources) {

        var annotated = annotate(stringToMap, type, sources).collect(Collectors.toList());

        var termIrisToResolve = annotated.stream()
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
