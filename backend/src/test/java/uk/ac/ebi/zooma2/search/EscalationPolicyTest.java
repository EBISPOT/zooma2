package uk.ac.ebi.zooma2.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.util.TermIds;

/** One decision, one criterion: when does a property need the deep search phase? */
class EscalationPolicyTest {

    private static MapResult result(String id, String iri, String foundIn, double confidence) {
        MapResult r = new MapResult();
        r.textToMap = "x";
        r.ontologyTermID = id;
        r.ontologyTermIri = iri;
        r.ontologyURI = foundIn;
        r.mappingConfidence = confidence;
        return r;
    }

    private static Filter targets(boolean definingOnly, String... ontologies) {
        return Filter.fromLists(null, null, List.of(ontologies), false, definingOnly);
    }

    private static final MapResult STRONG_ECTO = result("ECTO_9000460", "http://purl.obolibrary.org/obo/ECTO_9000460", "ecto", 0.9);
    private static final MapResult WEAK_ECTO = result("ECTO_9000461", "http://purl.obolibrary.org/obo/ECTO_9000461", "ecto", 0.3);
    private static final MapResult CHEBI = result("CHEBI_9937", "http://purl.obolibrary.org/obo/CHEBI_9937", "chebi", 1.0);

    @Test
    void explicitDeepAlwaysWinsEvenWithNothingFound() {
        assertTrue(EscalationPolicy.needsDeep(List.of(), targets(false, "ecto"), true, Set.of()));
        assertTrue(EscalationPolicy.needsDeep(List.of(STRONG_ECTO), targets(false, "ecto"), true, Set.of()));
        assertFalse(EscalationPolicy.needsDeep(List.of(), targets(false, "ecto"), false, Set.of()));
        assertFalse(EscalationPolicy.needsDeep(List.of(CHEBI), targets(false, "ecto"), false, Set.of()));
    }

    @Test
    void autoNeverEscalatesWithoutTargetOntologies() {
        assertFalse(EscalationPolicy.needsDeep(List.of(), null, null, Set.of()));
        assertFalse(EscalationPolicy.needsDeep(List.of(), Filter.fromLists(null, null, null, true), null, Set.of()));
    }

    @Test
    void autoEscalatesWhenNothingFromATargetOntologySettlesTheSearch() {
        assertTrue(EscalationPolicy.needsDeep(List.of(), targets(false, "ecto"), null, Set.of()), "empty Phase 1 is exactly when deep is needed");
        assertTrue(EscalationPolicy.needsDeep(List.of(CHEBI), targets(false, "ecto"), null, Set.of()));
        assertFalse(EscalationPolicy.needsDeep(List.of(CHEBI, STRONG_ECTO), targets(false, "ecto"), null, Set.of()));
    }

    @Test
    void aWeakTargetResultDoesNotSettleTheSearch() {
        // 0.3 would be suppressed downstream, leaving nothing from ECTO
        assertTrue(EscalationPolicy.needsDeep(List.of(CHEBI, WEAK_ECTO), targets(false, "ecto"), null, Set.of()));
        MapResult borderline = result("ECTO_1", "http://purl.obolibrary.org/obo/ECTO_1", "ecto", EscalationPolicy.MIN_SATISFYING_CONFIDENCE);
        assertFalse(EscalationPolicy.needsDeep(List.of(borderline), targets(false, "ecto"), null, Set.of()));
    }

    @Test
    void membershipMatchesTheOntologyFilter() {
        // An NCBITaxon term surfaced through EFO's file settles both an efo and an ncbitaxon target
        MapResult taxonViaEfo = result("NCBITaxon_10116", "http://purl.obolibrary.org/obo/NCBITaxon_10116", "efo", 1.0);
        assertFalse(EscalationPolicy.needsDeep(List.of(taxonViaEfo), targets(false, "ncbitaxon"), null, Set.of()));
        assertFalse(EscalationPolicy.needsDeep(List.of(taxonViaEfo), targets(false, "efo"), null, Set.of()));
        assertTrue(EscalationPolicy.needsDeep(List.of(taxonViaEfo), targets(false, "mondo"), null, Set.of()));
    }

    @Test
    void underDefiningOnlyAnImportedTermDoesNotSettleTheSearch() {
        MapResult chebiViaEcto = result("CHEBI_9937", "http://purl.obolibrary.org/obo/CHEBI_9937", "ecto", 1.0);
        assertFalse(EscalationPolicy.needsDeep(List.of(chebiViaEcto), targets(false, "ecto"), null, Set.of()));
        assertTrue(EscalationPolicy.needsDeep(List.of(chebiViaEcto), targets(true, "ecto"), null, Set.of()));
        assertFalse(EscalationPolicy.needsDeep(List.of(chebiViaEcto, STRONG_ECTO), targets(true, "ecto"), null, Set.of()));
    }

    @Test
    void anExcludedTermDoesNotSettleTheSearch() {
        Set<String> excluded = TermIds.forms("ECTO_9000460", "http://purl.obolibrary.org/obo/ECTO_9000460");
        assertTrue(EscalationPolicy.needsDeep(List.of(STRONG_ECTO), targets(false, "ecto"), null, excluded));
        assertFalse(EscalationPolicy.needsDeep(List.of(STRONG_ECTO), targets(false, "ecto"), null, TermIds.forms("ECTO_9000461", null)));
    }

    @Test
    void errorResultsAreIgnored() {
        assertTrue(EscalationPolicy.needsDeep(List.of(MapResult.error("x", null, "boom")), targets(false, "ecto"), null, Set.of()));
    }
}
