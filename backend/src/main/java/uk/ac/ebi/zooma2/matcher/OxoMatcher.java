package uk.ac.ebi.zooma2.matcher;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OxoClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Matcher that uses OXO (Ontology Xref Service) to find cross-references to preferred ontologies.
 * This matcher takes existing annotations and attempts to map them to the user's preferred ontologies
 * using OXO's cross-reference mappings.
 */
public class OxoMatcher implements AnnotationMatcher {

    private final OxoClient oxoClient;
    private final OlsClientRepo olsRepo;
    private final PrefixMap prefixMap = new PrefixMap();
    
    /** Maximum distance for OXO mappings (1 = direct, 2 = one hop, etc.) */
    private static final int DEFAULT_MAX_DISTANCE = 2;

    public OxoMatcher(OxoClient oxoClient, OlsClientRepo olsRepo) {
        this.oxoClient = oxoClient;
        this.olsRepo = olsRepo;
    }

    @Override
    public String getName() {
        return "OXO Cross-Reference";
    }

    /**
     * Takes existing annotations and attempts to expand them to preferred ontologies using OXO.
     * Only runs if targetOntologies is specified and previousResults exist.
     */
    @Override
    public List<Annotation> findMatches(MatchContext context) {
        // Only run if we have target ontologies specified
        if (context.targetOntologies == null || context.targetOntologies.isEmpty()) {
            return Collections.emptyList();
        }

        // Only run if we have previous results to expand
        if (context.previousResults == null || context.previousResults.isEmpty()) {
            return Collections.emptyList();
        }

        // Check if we already have results from target ontologies
        Set<String> preferredOntologiesLower = context.targetOntologies.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        
        boolean hasPreferredOntologyResults = context.previousResults.stream()
            .anyMatch(a -> {
                if (a.semanticTags == null || a.semanticTags.isEmpty()) {
                    return false;
                }
                String curie = prefixMap.iriToCurie(a.semanticTags.get(0));
                if (curie == null) {
                    return false;
                }
                String prefix = curie.split(":")[0].toLowerCase();
                return preferredOntologiesLower.contains(prefix);
            });
        
        if (hasPreferredOntologyResults) {
            System.err.println("OXO: Skipping expansion - already have results from target ontologies: " + 
                context.targetOntologies);
            return Collections.emptyList();
        }

        System.err.println("OXO: Attempting to expand " + context.previousResults.size() + 
            " results to target ontologies: " + context.targetOntologies);

        List<Annotation> expandedAnnotations = new ArrayList<>();

        // Group annotations by their semantic tags for batch processing
        Map<String, List<Annotation>> annotationsByTag = context.previousResults.stream()
            .filter(a -> a.semanticTags != null && !a.semanticTags.isEmpty())
            .collect(Collectors.groupingBy(a -> a.semanticTags.get(0), LinkedHashMap::new, Collectors.toList()));

        // Get unique term IRIs to query
        Set<String> termIris = annotationsByTag.keySet();
        
        // Convert IRIs to CURIEs for OXO (e.g., "DOID:3029" not "obo_DOID_3029")
        List<String> termIds = termIris.stream()
            .map(iri -> prefixMap.iriToCurie(iri))
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        if (termIds.isEmpty()) {
            return Collections.emptyList();
        }

        System.err.println("OXO: Querying " + termIds.size() + " term IDs: " + termIds);

        // Query OXO for mappings to target ontologies
        List<OxoClient.OxoMapping> oxoMappings = oxoClient.search(
            termIds,
            context.targetOntologies,
            DEFAULT_MAX_DISTANCE
        );

        System.err.println("OXO: Found " + oxoMappings.size() + " cross-reference mappings");

        if (oxoMappings.isEmpty()) {
            return Collections.emptyList();
        }

        // Resolve all target terms from OLS (but don't require them)
        Set<String> targetIris = oxoMappings.stream()
            .map(m -> prefixMap.shortFormToIri(m.targetId))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        
        Map<String, OlsTerm> targetTermMap = olsRepo.resolveTerms(targetIris);

        // Create new annotations for each OXO mapping
        for (OxoClient.OxoMapping mapping : oxoMappings) {
            String sourceIri = prefixMap.shortFormToIri(mapping.sourceId);
            String targetIri = prefixMap.shortFormToIri(mapping.targetId);
            
            if (sourceIri == null || targetIri == null) {
                continue;
            }

            // Get the source annotations that have this semantic tag
            List<Annotation> sourceAnnotations = annotationsByTag.get(sourceIri);
            if (sourceAnnotations == null || sourceAnnotations.isEmpty()) {
                continue;
            }

            // Get the target term from OLS (if available)
            OlsTerm targetTerm = targetTermMap.get(targetIri);
            if (targetTerm == null) {
                // Term not in OLS (e.g., UMLS) - use OXO's label instead
                System.err.println("OXO: Target term " + targetIri + " not in OLS, using OXO label: " + mapping.targetLabel);
            }

            // Create new annotation for each source annotation
            for (Annotation sourceAnnotation : sourceAnnotations) {
                Annotation expandedAnnotation = createExpandedAnnotation(
                    sourceAnnotation,
                    mapping,
                    targetTerm
                );
                expandedAnnotations.add(expandedAnnotation);
            }
        }

        System.err.println("OXO: Created " + expandedAnnotations.size() + " expanded annotations");
        return expandedAnnotations;
    }

    /**
     * Create a new annotation based on an existing one, but with a different target term reached via OXO.
     * targetTerm may be null if the term is not in OLS (e.g., UMLS).
     */
    private Annotation createExpandedAnnotation(
            Annotation sourceAnnotation,
            OxoClient.OxoMapping oxoMapping,
            OlsTerm targetTerm) {
        
        Annotation expandedAnnotation = new Annotation();
        
        // Copy the annotated property
        expandedAnnotation.annotatedProperty = new Annotation.AnnotatedProperty();
        expandedAnnotation.annotatedProperty.propertyType = sourceAnnotation.annotatedProperty.propertyType;
        expandedAnnotation.annotatedProperty.propertyValue = sourceAnnotation.annotatedProperty.propertyValue;
        
        // Set the new semantic tag to the OXO target IRI
        String targetIri = prefixMap.shortFormToIri(oxoMapping.targetId);
        expandedAnnotation.semanticTags = List.of(targetIri);
        expandedAnnotation.resolvedTerm = targetTerm;
        
        // Add OXO cross-reference step using the helper method; its confidence
        // falls with mapping distance (direct 0.95, one hop 0.85, further 0.75)
        V3MappingProvenanceStepDto oxoStep = V3MappingProvenanceStepDto.oxoMapping(
            oxoMapping.sourceId,
            oxoMapping.sourceLabel,
            oxoMapping.targetId,
            oxoMapping.targetLabel,
            oxoMapping.distance
        );

        // Indirect mapping: the seed's confidence scaled by the hop's own confidence
        expandedAnnotation.confidence = sourceAnnotation.confidence * oxoStep.confidence;
        
        // Copy provenance
        expandedAnnotation.provenance = new Annotation.Provenance();
        expandedAnnotation.provenance.source = new Annotation.Source();
        expandedAnnotation.provenance.source.type = "ONTOLOGY";
        // Use ontology name from OLS term if available, otherwise extract from target ID
        if (targetTerm != null) {
            expandedAnnotation.provenance.source.name = targetTerm.ontology_name;
            expandedAnnotation.provenance.source.uri = targetTerm.ontology_name;
        } else {
            // Extract ontology prefix from target ID (e.g., "UMLS" from "UMLS:C0004096")
            String ontologyPrefix = oxoMapping.targetId.split(":")[0].toLowerCase();
            expandedAnnotation.provenance.source.name = ontologyPrefix;
            expandedAnnotation.provenance.source.uri = ontologyPrefix;
        }
        expandedAnnotation.provenance.evidence = "OXO_CROSS_REFERENCE";
        expandedAnnotation.provenance.accuracy = "NOT_SPECIFIED";
        expandedAnnotation.provenance.generator = "ZOOMA";
        expandedAnnotation.provenance.generatedDate = new Date().toString();
        
        // Chain the provenance: include all steps from source annotation + add OXO step
        List<V3MappingProvenanceStepDto> newProvenance = new ArrayList<>();
        if (sourceAnnotation.mappingProvenance != null) {
            newProvenance.addAll(sourceAnnotation.mappingProvenance);
        }
        
        newProvenance.add(oxoStep);
        
        expandedAnnotation.mappingProvenance = newProvenance;
        
        // Link back to source for reference
        expandedAnnotation.sourceAnnotation = sourceAnnotation;
        
        return expandedAnnotation;
    }

}
