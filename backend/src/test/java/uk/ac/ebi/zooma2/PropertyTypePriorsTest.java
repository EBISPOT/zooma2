package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/** The property type is a hint about the value's kind, for every kind, not just organisms (issue #21). */
class PropertyTypePriorsTest {

    private static PrefixMap prefixMap;

    @BeforeAll
    static void init() {
        prefixMap = new PrefixMap();
    }

    private static Set<String> preferred(String type) {
        return Deduplicator.preferredNamespacesFor(type, Deduplicator.DEFAULT_PRIORS);
    }

    @Test
    void builtInTableCoversTheCommonPropertyTypes() {
        assertEquals(List.of("ncbitaxon"), List.copyOf(preferred("organism")));
        assertEquals(List.of("ncbitaxon"), List.copyOf(preferred("Species")));
        assertEquals(List.of("mondo", "efo"), List.copyOf(preferred("disease")));
        assertEquals(List.of("mondo", "efo"), List.copyOf(preferred("disease state")));
        assertEquals(List.of("hp", "mp", "efo", "oba"), List.copyOf(preferred("phenotype")));
        assertEquals(List.of("cl"), List.copyOf(preferred("cell type")));
        assertEquals(List.of("clo", "efo"), List.copyOf(preferred("cell line")));
        assertEquals(List.of("uberon", "efo"), List.copyOf(preferred("organism part")));
        assertEquals(List.of("chebi"), List.copyOf(preferred("compound")));
        assertEquals(List.of("uo"), List.copyOf(preferred("unit")));
        assertEquals(Set.of(), preferred("unspecified"));
        assertEquals(Set.of(), preferred("growth protocol"));
        assertEquals(Set.of(), preferred(null));
    }

    @Test
    void exclusionsKeepTheOrganismRuleAsItWas() {
        assertFalse(preferred("organism part").contains("ncbitaxon"));
        assertEquals(Set.of(), preferred("organism age"));
        assertFalse(preferred("cell line").contains("cl"));
        assertTrue(Deduplicator.isOrganismLikeType("organism"));
        assertFalse(Deduplicator.isOrganismLikeType("organism part"));
    }

    @Test
    void configuredPriorsReplaceTheTable() {
        var custom = new ZoomaConfig.PropertyTypePrior();
        custom.types = List.of("growth condition");
        custom.namespaces = List.of("PECO");
        var priors = List.of(custom);

        assertEquals(List.of("peco"), List.copyOf(Deduplicator.preferredNamespacesFor("Growth Condition", priors)));
        assertEquals(Set.of(), Deduplicator.preferredNamespacesFor("disease", priors));
        assertEquals(List.of("peco"), List.copyOf(new Deduplicator(prefixMap, priors).preferredNamespacesFor("growth condition", priors)));
    }

    private static MapResult result(String type, String iri, String ontology, double confidence, String method, String matchType) {
        MapResult r = new MapResult();
        r.textToMap = "value";
        r.propertyType = type;
        r.ontologyTermIri = iri;
        r.ontologyTermID = iri.substring(iri.lastIndexOf('/') + 1);
        r.ontologyURI = ontology;
        r.datasource = ontology;
        r.mappingConfidence = confidence;
        var step = new V3MappingProvenanceStepDto();
        step.method = method;
        step.matchType = matchType;
        step.similarity = confidence;
        step.confidence = confidence;
        r.mappingProvenance = List.of(step);
        return r;
    }

    @Test
    void aGroundedMatchInAPreferredNamespaceBoostsItAndDemotesTheRest() {
        var dedup = new Deduplicator(prefixMap);
        MapResult mondo = result("disease", "http://purl.obolibrary.org/obo/MONDO_0005015", "mondo", 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult efo = result("disease", "http://www.ebi.ac.uk/efo/EFO_0000400", "efo", 0.9, "lexical", "OLS_TEXT_TAGGER_SYNONYM");
        MapResult chebi = result("disease", "http://purl.obolibrary.org/obo/CHEBI_17234", "chebi", 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult mondoGuess = result("disease", "http://purl.obolibrary.org/obo/MONDO_0000001", "mondo", 0.8, "semantic", "OLS_EMBEDDING");
        List<MapResult> results = new ArrayList<>(List.of(mondo, efo, chebi, mondoGuess));

        dedup.preferNamespacesForPropertyType(results);

        assertEquals(1.0, mondo.mappingConfidence, 1e-9);
        assertEquals(1.0, efo.mappingConfidence, 1e-9);
        assertEquals(0.9, chebi.mappingConfidence, 1e-9);
        assertEquals(0.8, mondoGuess.mappingConfidence, 1e-9, "an embedding guess in the preferred namespace is neither boosted nor demoted");
    }

    @Test
    void withoutAGroundedPreferredMatchNothingChanges() {
        var dedup = new Deduplicator(prefixMap);
        MapResult chebi = result("disease", "http://purl.obolibrary.org/obo/CHEBI_17234", "chebi", 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult mondoGuess = result("disease", "http://purl.obolibrary.org/obo/MONDO_0000001", "mondo", 0.8, "semantic", "OLS_EMBEDDING");
        List<MapResult> results = new ArrayList<>(List.of(chebi, mondoGuess));

        dedup.preferNamespacesForPropertyType(results);

        assertEquals(1.0, chebi.mappingConfidence, 1e-9);
        assertEquals(0.8, mondoGuess.mappingConfidence, 1e-9);

        MapResult untyped = result("growth protocol", "http://purl.obolibrary.org/obo/CHEBI_17234", "chebi", 1.0, "lexical", "OLS_TEXT_TAGGER");
        MapResult untypedMondo = result("growth protocol", "http://purl.obolibrary.org/obo/MONDO_0005015", "mondo", 1.0, "lexical", "OLS_TEXT_TAGGER");
        results = new ArrayList<>(List.of(untyped, untypedMondo));
        dedup.preferNamespacesForPropertyType(results);
        assertEquals(1.0, untyped.mappingConfidence, 1e-9);
        assertEquals(1.0, untypedMondo.mappingConfidence, 1e-9);
    }
}
