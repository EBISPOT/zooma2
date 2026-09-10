package uk.ac.ebi.zooma2.prefix_map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Short forms must expand to the IRIs OLS uses, whatever their casing, and IRIs
 * must render as the case-preserving short forms OLS reports. Needs the network
 * (Bioregistry), fetched once.
 */
class PrefixMapRoundTripTest {

    private static PrefixMap prefixMap;

    @BeforeAll
    static void load() {
        prefixMap = new PrefixMap();
    }

    @Test
    void ontologiesThatUsedToHaveStaleConfigEntriesNowResolveToOlsIris() {
        // Before: efo -> http://www.ebi.ac.uk/efo/0000400 (missing EFO_), cl/ncbitaxon/bto/pato -> purl.org/obo/owl#
        assertEquals("http://www.ebi.ac.uk/efo/EFO_0000400", prefixMap.shortFormToIri("EFO_0000400"));
        assertEquals("http://www.ebi.ac.uk/efo/EFO_0000400", prefixMap.shortFormToIri("efo_0000400"));
        assertEquals("http://www.ebi.ac.uk/efo/EFO_0000400", prefixMap.shortFormToIri("EFO:0000400"));
        assertEquals("http://purl.obolibrary.org/obo/CL_0000000", prefixMap.shortFormToIri("CL_0000000"));
        assertEquals("http://purl.obolibrary.org/obo/CL_0000000", prefixMap.shortFormToIri("cl_0000000"));
        assertEquals("http://purl.obolibrary.org/obo/NCBITaxon_10116", prefixMap.shortFormToIri("NCBITaxon_10116"));
        assertEquals("http://purl.obolibrary.org/obo/NCBITaxon_10116", prefixMap.shortFormToIri("ncbitaxon_10116"));
        assertEquals("http://purl.obolibrary.org/obo/BTO_0000000", prefixMap.shortFormToIri("BTO_0000000"));
        assertEquals("http://purl.obolibrary.org/obo/PATO_0000001", prefixMap.shortFormToIri("PATO_0000001"));
    }

    @Test
    void multiUnderscoreShortFormsExpandToTheTermIri() {
        assertEquals("http://edamontology.org/data_0006", prefixMap.shortFormToIri("EDAM_data_0006"));
        assertEquals("http://edamontology.org/topic_0621", prefixMap.shortFormToIri("EDAM_topic_0621"));
        // and a local id with an underscore under a prefix that has no sub-namespaces
        assertEquals("http://purl.obolibrary.org/obo/NCBITaxon_10116", prefixMap.shortFormToIri("NCBITaxon_10116"));
    }

    @Test
    void irisAndUnexpandableIdsPassThrough() {
        assertEquals("http://purl.obolibrary.org/obo/GO_0000003", prefixMap.shortFormToIri("http://purl.obolibrary.org/obo/GO_0000003"));
        assertEquals("C538249", prefixMap.shortFormToIri("C538249"));
        assertEquals("NOSUCHPREFIX_1", prefixMap.shortFormToIri("NOSUCHPREFIX_1"));
        assertNull(prefixMap.shortFormToIri(null));
    }

    @Test
    void iriToShortFormPreservesOlsCasing() {
        assertEquals("EFO_0000400", prefixMap.iriToShortForm("http://www.ebi.ac.uk/efo/EFO_0000400"));
        assertEquals("CHEBI_15377", prefixMap.iriToShortForm("http://purl.obolibrary.org/obo/CHEBI_15377"));
        assertEquals("NCBITaxon_10116", prefixMap.iriToShortForm("http://purl.obolibrary.org/obo/NCBITaxon_10116"));
        assertEquals("Orphanet_224", prefixMap.iriToShortForm("http://www.orpha.net/ORDO/Orphanet_224"));
        assertEquals("SIO_010001", prefixMap.iriToShortForm("http://semanticscience.org/resource/SIO_010001"));
        assertNull(prefixMap.iriToShortForm("http://example.org/"));
    }

    @Test
    void roundTrips() {
        for (String sf : new String[] {"EFO_0000400", "CL_0000000", "NCBITaxon_10116", "GO_0000003", "MONDO_0005015", "Orphanet_166"}) {
            assertEquals(sf, prefixMap.iriToShortForm(prefixMap.shortFormToIri(sf)), sf);
        }
        for (String iri : new String[] {"http://www.ebi.ac.uk/efo/EFO_0000400", "http://purl.obolibrary.org/obo/CL_0000000",
                "http://purl.obolibrary.org/obo/BTO_0000000", "http://purl.obolibrary.org/obo/PATO_0000001"}) {
            assertEquals(iri, prefixMap.shortFormToIri(prefixMap.iriToShortForm(iri)), iri);
        }
        // An IRI whose local part lacks the prefix (EDAM's data_0006) cannot round-trip
        // without ontology context; that context is applied by OlsClientRepo.olsShortForm.
        assertEquals("data_0006", prefixMap.iriToShortForm("http://edamontology.org/data_0006"));
    }
}
