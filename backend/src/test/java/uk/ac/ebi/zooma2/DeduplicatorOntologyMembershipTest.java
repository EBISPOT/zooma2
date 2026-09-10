package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/**
 * Ontology filtering, deduplication and exclusion must not depend on how a term
 * id happens to be rendered (issue #12).
 */
class DeduplicatorOntologyMembershipTest {

    private static Deduplicator dedup;

    @BeforeAll
    static void init() {
        dedup = new Deduplicator(new PrefixMap()); // fetches the Bioregistry once
    }

    private static MapResult result(String id, String iri, String foundIn, double confidence, String matchType) {
        MapResult r = new MapResult();
        r.textToMap = "x";
        r.ontologyTermID = id;
        r.ontologyTermIri = iri;
        r.ontologyURI = foundIn;
        r.mappingConfidence = confidence;
        var step = new V3MappingProvenanceStepDto();
        step.method = matchType.startsWith("CURATED") ? "curated" : matchType.equals("OLS_EMBEDDING") ? "semantic" : "lexical";
        step.matchType = matchType;
        step.similarity = 1.0;
        step.confidence = 1.0;
        r.mappingProvenance = List.of(step);
        return r;
    }

    private static Filter hard(String... ontologies) {
        return Filter.fromLists(null, null, List.of(ontologies), false);
    }

    @Test
    void termIsAvailableInBothItsDefiningAndItsImportingOntology() {
        // NCBITaxon_10116 surfaced through EFO's file
        MapResult viaEfo = result("NCBITaxon_10116", "http://purl.obolibrary.org/obo/NCBITaxon_10116", "efo", 1.0, "OLS_TEXT_TAGGER");

        List<MapResult> results = new ArrayList<>(List.of(viaEfo));
        dedup.filterByOntologies(results, hard("ncbitaxon"));
        assertEquals(1, results.size(), "defining namespace counts even though the term came from EFO's file");

        results = new ArrayList<>(List.of(viaEfo));
        dedup.filterByOntologies(results, hard("efo"));
        assertEquals(1, results.size(), "the plain filter keeps imports, as the defining_only cases document");

        results = new ArrayList<>(List.of(viaEfo));
        dedup.filterByOntologies(results, hard("mondo"));
        assertTrue(results.isEmpty());
    }

    @Test
    void definingOnlyStillNarrowsToTheNamespace() {
        MapResult chebiViaEcto = result("CHEBI_9937", "http://purl.obolibrary.org/obo/CHEBI_9937", "ecto", 1.0, "OLS_TEXT_TAGGER");
        MapResult ectoOwn = result("ECTO_9000460", "http://purl.obolibrary.org/obo/ECTO_9000460", "ecto", 0.8, "OLS_EMBEDDING");
        Filter definingOnly = Filter.fromLists(null, null, List.of("ecto"), false, true);

        List<MapResult> results = new ArrayList<>(List.of(chebiViaEcto, ectoOwn));
        dedup.filterByOntologies(results, definingOnly);
        assertEquals(2, results.size());
        dedup.filterToDefiningNamespace(results, definingOnly);
        assertEquals(List.of(ectoOwn), results);
    }

    @Test
    void bestPerTargetOntologyCountsATermUnderItsDefiningOntology() {
        MapResult taxonViaEfo = result("NCBITaxon_10116", "http://purl.obolibrary.org/obo/NCBITaxon_10116", "efo", 1.0, "OLS_TEXT_TAGGER");
        MapResult efoOwn = result("EFO_0000400", "http://www.ebi.ac.uk/efo/EFO_0000400", "efo", 0.9, "OLS_TEXT_TAGGER_SYNONYM");
        List<MapResult> results = new ArrayList<>(List.of(taxonViaEfo, efoOwn));

        dedup.keepBestPerTargetOntology(results, hard("efo", "ncbitaxon"));

        assertEquals(2, results.size(), "one is EFO's best, the other is NCBITaxon's best; neither displaces the other");
    }

    @Test
    void duplicatesAreMergedByIriWhateverTheIdRendering() {
        MapResult taggerLowercase = result("efo_0000400", "http://www.ebi.ac.uk/efo/EFO_0000400", "efo", 1.0, "OLS_TEXT_TAGGER");
        MapResult lexicalOls = result("EFO_0000400", "http://www.ebi.ac.uk/efo/EFO_0000400", "efo", 1.0, "OLS_LEXICAL_FUZZY_LABEL");
        MapResult meshRawIri = result("http://id.nlm.nih.gov/mesh/D000686", "http://id.nlm.nih.gov/mesh/D000686", "mesh", 0.7, "OLS_TEXT_TAGGER_SUBSTRING");
        MapResult meshOls = result("mesh_D000686", "http://id.nlm.nih.gov/mesh/D000686", "mesh", 0.85, "OLS_EMBEDDING");

        List<MapResult> deduped = dedup.deduplicateByTermId(new ArrayList<>(List.of(taggerLowercase, lexicalOls, meshRawIri, meshOls)));

        assertEquals(2, deduped.size());
        assertTrue(deduped.contains(taggerLowercase), "equal score: tagger report kept");
        assertTrue(deduped.contains(meshOls), "higher score kept");
    }

    @Test
    void exclusionMatchesAnyRenderingOfTheSameTerm() {
        MapResult efo = result("EFO_0000400", "http://www.ebi.ac.uk/efo/EFO_0000400", "efo", 1.0, "OLS_TEXT_TAGGER");
        MapResult mesh = result("mesh_D000686", "http://id.nlm.nih.gov/mesh/D000686", "mesh", 0.85, "OLS_EMBEDDING");
        MapResult other = result("EFO_0000401", "http://www.ebi.ac.uk/efo/EFO_0000401", "efo", 0.6, "OLS_EMBEDDING");

        for (String excluded : new String[] {"EFO_0000400", "efo_0000400", "EFO:0000400", "http://www.ebi.ac.uk/efo/EFO_0000400"}) {
            List<MapResult> results = new ArrayList<>(List.of(efo, mesh, other));
            dedup.excludeTerms(results, List.of(excluded));
            assertEquals(List.of(mesh, other), results, "excluding " + excluded);
        }
        for (String excluded : new String[] {"mesh_D000686", "MESH_D000686", "http://id.nlm.nih.gov/mesh/D000686"}) {
            List<MapResult> results = new ArrayList<>(List.of(efo, mesh, other));
            dedup.excludeTerms(results, List.of(excluded));
            assertEquals(List.of(efo, other), results, "excluding " + excluded);
        }
    }
}
