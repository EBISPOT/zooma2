package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Reproduces OLS4's ShortFormAnnotator.extractShortForm for the ontologies whose IRIs don't carry their prefix. */
class OlsShortFormRuleTest {

    @Test
    void efoStyleIriUnderConfiguredBase() {
        assertEquals("EFO_0000400", OlsShortForms.shortForm("efo", "EFO", List.of("http://www.ebi.ac.uk/efo/EFO_"), "http://www.ebi.ac.uk/efo/EFO_0000400"));
    }

    @Test
    void prefixIsPrependedWhenTheIriTailLacksIt() {
        assertEquals("mesh_D000686", OlsShortForms.shortForm("mesh", "mesh", List.of("http://id.nlm.nih.gov/mesh/"), "http://id.nlm.nih.gov/mesh/D000686"));
        assertEquals("SNOMED_60961000119107", OlsShortForms.shortForm("snomed", "SNOMED", List.of("http://snomed.info/id/"), "http://snomed.info/id/60961000119107"));
        assertEquals("hgnc_5", OlsShortForms.shortForm("hgnc", "hgnc", List.of("https://www.genenames.org/data/gene-symbol-report/#!/hgnc_id/"), "https://www.genenames.org/data/gene-symbol-report/#!/hgnc_id/5"));
    }

    @Test
    void baseThatEndsInsideTheTailIsStripped() {
        // ORDO's IRIs say Orphanet_ but OLS's base URI covers that token, so the id is ORDO_224 (verified live)
        assertEquals("ORDO_224", OlsShortForms.shortForm("ordo", "ORDO", List.of("http://www.orpha.net/ORDO/Orphanet_"), "http://www.orpha.net/ORDO/Orphanet_224"));
    }

    @Test
    void oboPurlWithoutConfiguredBasesUsesTheDerivedOboBase() {
        assertEquals("CHEBI_15377", OlsShortForms.shortForm("chebi", "CHEBI", null, "http://purl.obolibrary.org/obo/CHEBI_15377"));
        assertEquals("NCBITaxon_10116", OlsShortForms.shortForm("ncbitaxon", "NCBITaxon", List.of(), "http://purl.obolibrary.org/obo/NCBITaxon_10116"));
    }

    @Test
    void missingPreferredPrefixDefaultsToUpperCaseOntologyId() {
        assertEquals("FOODON_03411345", OlsShortForms.shortForm("foodon", null, null, "http://purl.obolibrary.org/obo/FOODON_03411345"));
        assertEquals("FOODON_03411345", OlsShortForms.shortForm("foodon", "", null, "http://purl.obolibrary.org/obo/FOODON_03411345"));
    }

    @Test
    void iriMatchingNoBaseFallsBackToTheLocalPart() {
        assertEquals("SIO_010001", OlsShortForms.shortForm("sio", "SIO", List.of("http://semanticscience.org/resource/SIO_"), "http://example.org/other#SIO_010001"));
        assertEquals("Orphanet_224", OlsShortForms.shortForm("efo", "EFO", List.of("http://www.ebi.ac.uk/efo/EFO_"), "http://www.orpha.net/ORDO/Orphanet_224"));
        assertEquals("EFO_0000400", OlsShortForms.shortForm(null, null, null, "http://www.ebi.ac.uk/efo/EFO_0000400"));
    }

    @Test
    void edamFollowsTheConfiguredBaseRule() {
        // OLS's own index currently reports EDAM_data_0849 for this term, which its
        // present code would not produce; the rule is followed as written and the
        // difference is harmless because results are keyed by IRI.
        assertEquals("EDAM_0849", OlsShortForms.shortForm("edam", "EDAM", List.of("http://edamontology.org/data_", "http://edamontology.org/topic_"), "http://edamontology.org/data_0849"));
    }

    @Test
    void urnAndNullHandling() {
        assertEquals("x:y", OlsShortForms.shortForm("o", "O", null, "urn:x:y"));
        assertNull(OlsShortForms.shortForm("o", "O", null, null));
        assertNull(OlsShortForms.localPart("http://example.org/"));
        assertEquals("plain", OlsShortForms.localPart("plain"));
    }
}
