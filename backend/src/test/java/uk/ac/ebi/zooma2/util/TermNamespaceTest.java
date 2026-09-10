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
