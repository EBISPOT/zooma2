package uk.ac.ebi.zooma2.api.v2.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Filter;

/**
 * The legacy V2 sentinel {@code ontologies:[none]} (issue #16) must translate
 * to "no ontology restriction" rather than a literal ontology named "none".
 */
class V2FilterNoneSentinelTest {

    @Test
    void ontologiesNoneMeansNoOntologyRestriction() {
        Filter f = V2FilterDto.parse("ontologies:[none]").toFilter();
        assertTrue(f.targetOntologies.isEmpty());
        assertTrue(f.includeOtherOntologies);
    }

    @Test
    void ontologiesSelectNoneMeansNoOntologyRestriction() {
        Filter f = V2FilterDto.parse("ontologies:[Select None]").toFilter();
        assertTrue(f.targetOntologies.isEmpty());
        assertTrue(f.includeOtherOntologies);
    }

    @Test
    void sentinelIsCaseInsensitive() {
        assertTrue(V2FilterNoneSentinelTest.isNone("NONE"));
        assertTrue(V2FilterNoneSentinelTest.isNone("select none"));
        assertFalse(V2FilterNoneSentinelTest.isNone("efo"));
    }

    @Test
    void requiredAtlasWithOntologiesNoneKeepsRequiredAndDropsOntologies() {
        Filter f = V2FilterDto.parse("required:[atlas],ontologies:[none]").toFilter();
        assertEquals(List.of("atlas"), f.required);
        assertTrue(f.targetOntologies.isEmpty());
        assertTrue(f.includeOtherOntologies);
    }

    @Test
    void requiredNoneIsNotTranslated() {
        // required:[none] is a different sentinel (no curated sources) and is
        // passed through unchanged; only the ontologies list is special-cased.
        Filter f = V2FilterDto.parse("required:[none],ontologies:[efo]").toFilter();
        assertEquals(List.of("none"), f.required);
        assertEquals(List.of("efo"), f.targetOntologies);
        assertFalse(f.includeOtherOntologies);
    }

    @Test
    void normalOntologiesListIsAHardFilter() {
        Filter f = V2FilterDto.parse("ontologies:[efo,mondo]").toFilter();
        assertEquals(List.of("efo", "mondo"), f.targetOntologies);
        assertFalse(f.includeOtherOntologies);
    }

    @Test
    void noneAmongRealOntologiesIsNotASentinel() {
        // The sentinel is only a lone value; a multi-value list is taken literally.
        Filter f = V2FilterDto.parse("ontologies:[none,efo]").toFilter();
        assertEquals(List.of("none", "efo"), f.targetOntologies);
        assertFalse(f.includeOtherOntologies);
    }

    @Test
    void emptyFilterHasNoRestrictions() {
        Filter f = V2FilterDto.parse("").toFilter();
        assertTrue(f.required.isEmpty());
        assertTrue(f.preferred.isEmpty());
        assertTrue(f.targetOntologies.isEmpty());
        assertTrue(f.includeOtherOntologies);
        assertFalse(f.definingOnly);
    }

    @Test
    void definingOnlyWithSentinelIsHarmless() {
        // definingOnly only acts on target ontologies; with the sentinel there
        // are none, so the flag is carried but has nothing to restrict.
        Filter f = V2FilterDto.parse("ontologies:[none],defining_only:[true]").toFilter();
        assertTrue(f.targetOntologies.isEmpty());
        assertTrue(f.includeOtherOntologies);
        assertTrue(f.definingOnly);
    }

    private static boolean isNone(String value) {
        return V2FilterDto.isNoneSentinel(List.of(value));
    }
}
