package uk.ac.ebi.zooma2.prefix_map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A cold start must not depend on GitHub: the vendored snapshot alone resolves the prefixes the pipeline needs. */
class BioregistrySnapshotTest {

    private static final Bioregistry SNAPSHOT = Bioregistry.fromSnapshot(); // no network

    @Test
    void snapshotIsPresentAndPopulated() {
        assertTrue(SNAPSHOT.size() > 2000, "entries: " + SNAPSHOT.size());
        assertNull(SNAPSHOT.getRegistryUrl(), "snapshot-only instance never refreshes");
    }

    @Test
    void resolvesTheOntologiesThePipelineDependsOn() {
        assertEquals("http://www.ebi.ac.uk/efo/EFO_0000400", SNAPSHOT.getUrlForId("EFO", "0000400"));
        assertEquals("http://www.ebi.ac.uk/efo/EFO_0000400", SNAPSHOT.getUrlForId("efo", "0000400"));
        assertEquals("http://purl.obolibrary.org/obo/CL_0000000", SNAPSHOT.getUrlForId("CL", "0000000"));
        assertEquals("http://purl.obolibrary.org/obo/NCBITaxon_10116", SNAPSHOT.getUrlForId("NCBITaxon", "10116"));
        assertEquals("http://purl.obolibrary.org/obo/GO_0000003", SNAPSHOT.getUrlForId("GO", "0000003"));
        assertEquals("http://purl.obolibrary.org/obo/MONDO_0005015", SNAPSHOT.getUrlForId("MONDO", "0005015"));
        assertEquals("http://edamontology.org/data_0006", SNAPSHOT.getUrlForId("edam.data", "0006"));
        assertEquals("http://purl.obolibrary.org/obo/BTO_0000000", SNAPSHOT.getUrlForId("BTO", "0000000"));
        assertEquals("http://purl.obolibrary.org/obo/PATO_0000001", SNAPSHOT.getUrlForId("PATO", "0000001"));
    }

    @Test
    void rejectsIdsThatDoNotMatchThePrefixPattern() {
        assertNull(SNAPSHOT.getUrlForId("EFO", "not-an-id"));
        assertNull(SNAPSHOT.getUrlForId("NOSUCHPREFIX", "1"));
        assertNull(SNAPSHOT.getUrlForId("EFO", null));
    }

    @Test
    void mapsIrisBackToCuries() {
        assertEquals("efo:0000400", SNAPSHOT.getCurieForUrl("http://www.ebi.ac.uk/efo/EFO_0000400"));
        assertEquals("go:0000003", SNAPSHOT.getCurieForUrl("http://purl.obolibrary.org/obo/GO_0000003"));
        assertNull(SNAPSHOT.getCurieForUrl("http://example.org/nothing/1"));
    }

    @Test
    void prefixMapWorksOfflineOnTheSnapshot() {
        PrefixMap offline = new PrefixMap(SNAPSHOT);
        assertEquals("http://www.ebi.ac.uk/efo/EFO_0000400", offline.shortFormToIri("EFO_0000400"));
        assertEquals("http://purl.obolibrary.org/obo/NCBITaxon_10116", offline.shortFormToIri("ncbitaxon_10116"));
    }
}
