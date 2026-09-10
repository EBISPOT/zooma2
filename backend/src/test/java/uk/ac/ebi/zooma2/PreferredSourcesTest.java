package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingCandidateDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.matcher.EvidenceTier;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.Bioregistry;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/** preferred:[...] flags candidates from the named sources and ranks them ahead of equals (issue #23, item 1). */
class PreferredSourcesTest {

    private static final Deduplicator DEDUP = new Deduplicator(new PrefixMap(Bioregistry.fromSnapshot()));

    private static MapResult result(String id, String ontology, String datasource, double confidence, String method, String matchType) {
        MapResult r = new MapResult();
        r.textToMap = "x";
        r.ontologyTermID = id;
        r.ontologyTermIri = "http://purl.obolibrary.org/obo/" + id;
        r.ontologyURI = ontology;
        r.datasource = datasource;
        r.mappingConfidence = confidence;
        var step = new V3MappingProvenanceStepDto();
        step.method = method;
        step.matchType = matchType;
        step.similarity = 1.0;
        step.confidence = 1.0;
        r.mappingProvenance = List.of(step);
        return r;
    }

    @Test
    void flagsPreferredDatasourcesAndOntologies() {
        MapResult atlas = result("EFO_1", "efo", "atlas", 1.0, "curated", "CURATED_EXACT");
        MapResult gwas = result("EFO_2", "efo", "gwas", 1.0, "curated", "CURATED_EXACT");
        MapResult mondo = result("MONDO_3", "mondo", "mondo", 0.9, "lexical", "OLS_TEXT_TAGGER_SYNONYM");
        MapResult taxonViaEfo = result("NCBITaxon_4", "efo", "efo", 0.8, "semantic", "OLS_EMBEDDING");
        List<MapResult> results = new ArrayList<>(List.of(atlas, gwas, mondo, taxonViaEfo));

        DEDUP.markPreferred(results, Filter.fromLists(null, List.of("GWAS", "ncbitaxon"), null, true));

        assertNull(atlas.preferred);
        assertEquals(Boolean.TRUE, gwas.preferred, "datasource match, case-insensitive");
        assertNull(mondo.preferred);
        assertEquals(Boolean.TRUE, taxonViaEfo.preferred, "defining-namespace match counts as the ontology");
    }

    @Test
    void noPreferredListLeavesEverythingUnset() {
        MapResult r = result("EFO_1", "efo", "atlas", 1.0, "curated", "CURATED_EXACT");
        List<MapResult> results = new ArrayList<>(List.of(r));
        DEDUP.markPreferred(results, Filter.fromLists(null, null, null, true));
        DEDUP.markPreferred(results, null);
        assertNull(r.preferred);
    }

    @Test
    void preferredBreaksTiesButNeverOverridesConfidence() {
        MapResult stronger = result("EFO_1", "efo", "atlas", 1.0, "curated", "CURATED_EXACT");
        MapResult preferredButWeaker = result("EFO_2", "efo", "gwas", 0.9, "curated", "CURATED_EXACT");
        preferredButWeaker.preferred = true;
        MapResult tiedPreferred = result("EFO_3", "efo", "gwas", 1.0, "lexical", "OLS_TEXT_TAGGER");
        tiedPreferred.preferred = true;
        MapResult tiedCurated = result("EFO_4", "efo", "atlas", 1.0, "curated", "CURATED_EXACT");

        List<MapResult> sorted = new ArrayList<>(List.of(preferredButWeaker, tiedCurated, tiedPreferred, stronger));
        sorted.sort(EvidenceTier.resultRanking());

        // 1.0 preferred label match beats the 1.0 non-preferred curated matches (which keep
        // their input order between themselves); confidence still comes first
        assertEquals(List.of(tiedPreferred, tiedCurated, stronger, preferredButWeaker), sorted);
        assertTrue(EvidenceTier.rankOf(tiedCurated.mappingProvenance) < EvidenceTier.rankOf(tiedPreferred.mappingProvenance),
            "tier alone would have put the curated match first");
    }

    @Test
    void candidateDtoCarriesTermIriAndPreferredFlag() {
        MapResult r = result("EFO_1", "efo", "gwas", 1.0, "curated", "CURATED_EXACT");
        r.preferred = true;
        V3MappingCandidateDto dto = V3MappingCandidateDto.from(r);
        assertEquals("http://purl.obolibrary.org/obo/EFO_1", dto.uri, "uri is the term IRI, not the ontology name");
        assertEquals("efo", dto.ontology);
        assertEquals(Boolean.TRUE, dto.preferred);
        assertNull(V3MappingCandidateDto.from(result("EFO_2", "efo", "atlas", 1.0, "curated", "CURATED_EXACT")).preferred);

        List<V3MappingCandidateDto> sorted = new ArrayList<>(List.of(
            V3MappingCandidateDto.from(result("EFO_2", "efo", "atlas", 1.0, "curated", "CURATED_EXACT")), dto));
        sorted.sort(EvidenceTier.candidateRanking());
        assertEquals(dto, sorted.get(0));
    }
}
