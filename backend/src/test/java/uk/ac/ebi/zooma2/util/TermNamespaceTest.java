package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class TermNamespaceTest {

    @Test
    void prefixOfShortForm() {
        assertEquals("ecto", TermNamespace.prefixOf("ECTO_9000460"));
        assertEquals("chebi", TermNamespace.prefixOf("CHEBI_9937"));
    }

    @Test
    void prefixEndsAtTheFirstUnderscore() {
        // Local ids may contain underscores; OBO/OLS prefixes never do (issue #12)
        assertEquals("edam", TermNamespace.prefixOf("EDAM_data_0006"));
        assertEquals("edam", TermNamespace.prefixOf("EDAM_topic_0621"));
        assertEquals("ncbitaxon", TermNamespace.prefixOf("NCBITaxon_10116"));
        assertEquals("mesh", TermNamespace.prefixOf("mesh_D000686"));
    }

    @Test
    void iriWhoseLocalPartLacksThePrefixIsJudgedByThatLocalPart() {
        // Callers pass the OLS short form (EDAM_data_0006) when they have it; from
        // the bare IRI only the local part is visible.
        assertEquals("data", TermNamespace.prefixOf("http://edamontology.org/data_0006"));
        assertNull(TermNamespace.prefixOf("http://id.nlm.nih.gov/mesh/D000686"));
    }

    @Test
    void prefixOfCurie() {
        assertEquals("efo", TermNamespace.prefixOf("EFO:0000400"));
    }

    @Test
    void prefixOfIri() {
        assertEquals("ncbitaxon", TermNamespace.prefixOf("http://purl.obolibrary.org/obo/NCBITaxon_10116"));
        assertEquals("efo", TermNamespace.prefixOf("http://www.ebi.ac.uk/efo/EFO_0000400"));
    }

    @Test
    void prefixOfUnprefixedIdIsNull() {
        assertNull(TermNamespace.prefixOf("123456"));
        assertNull(TermNamespace.prefixOf(null));
        assertNull(TermNamespace.prefixOf("  "));
    }

    @Test
    void inNamespacesTreatsUnprefixedIdsAsMembers() {
        assertTrue(TermNamespace.inNamespaces("ECTO_9000460", Set.of("ecto")));
        assertFalse(TermNamespace.inNamespaces("CHEBI_9937", Set.of("ecto")));
        assertTrue(TermNamespace.inNamespaces("123456", Set.of("ecto")));
    }
}
