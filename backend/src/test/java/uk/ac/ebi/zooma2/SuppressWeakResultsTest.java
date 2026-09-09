package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;

/**
 * Behaviour of {@link Deduplicator#suppressWeakResults} (issue #11): the
 * tight 0.05 gap must respect the ontology filter mode and must never delete
 * the ontologies the caller asked for.
 */
class SuppressWeakResultsTest {

    /** PrefixMap fetches the Bioregistry over the network; share one instance across all tests. */
    private static final PrefixMap PREFIX_MAP = new PrefixMap();

    private final Deduplicator deduplicator = new Deduplicator(PREFIX_MAP);

    // ---- no target ontologies: unchanged 0.2 floor ----

    @Test
    void noTargetsKeepsResultsWithinPointTwoOfBest() {
        var results = results(
            result("FOODON_1", "foodon", 1.0),
            result("EFO_1", "efo", 0.85),
            result("MONDO_1", "mondo", 0.79));

        deduplicator.suppressWeakResults(results, Filter.fromLists(null, null, null, true));

        assertEquals(List.of("FOODON_1", "EFO_1"), ids(results));
    }

    @Test
    void noTargetsAndWeakBestDropsNothing() {
        var results = results(
            result("FOODON_1", "foodon", 0.65),
            result("EFO_1", "efo", 0.3));

        deduplicator.suppressWeakResults(results, null);

        assertEquals(List.of("FOODON_1", "EFO_1"), ids(results));
    }

    // ---- soft preference (targets set, includeOtherOntologies=true) ----

    @Test
    void softPreferenceStrongNonTargetDoesNotDeleteTargetBest() {
        // The example from the issue: a FOODON label match must not delete the
        // best EFO candidate when EFO is what the caller asked to prioritise.
        var results = results(
            result("FOODON_1", "foodon", 1.0),
            result("EFO_1", "efo", 0.85));

        deduplicator.suppressWeakResults(results, soft("efo"));

        assertEquals(List.of("FOODON_1", "EFO_1"), ids(results));
    }

    @Test
    void softPreferenceDropsNonTargetNearMiss() {
        var results = results(
            result("EFO_1", "efo", 1.0),
            result("FOODON_1", "foodon", 0.97),
            result("MONDO_1", "mondo", 0.9));

        deduplicator.suppressWeakResults(results, soft("efo"));

        assertEquals(List.of("EFO_1"), ids(results));
    }

    @Test
    void softPreferenceKeepsNonTargetClearlyAboveTarget() {
        var results = results(
            result("FOODON_1", "foodon", 1.0),
            result("EFO_1", "efo", 0.9));

        deduplicator.suppressWeakResults(results, soft("efo"));

        assertEquals(List.of("FOODON_1", "EFO_1"), ids(results));
    }

    @Test
    void softPreferenceStillAppliesFloorToTargetResults() {
        // Target results are exempt from the tight gap, not from the floor.
        var results = results(
            result("FOODON_1", "foodon", 1.0),
            result("EFO_1", "efo", 0.75));

        deduplicator.suppressWeakResults(results, soft("efo"));

        assertEquals(List.of("FOODON_1"), ids(results));
    }

    @Test
    void softPreferenceWithoutTargetResultsOnlyAppliesFloor() {
        var results = results(
            result("FOODON_1", "foodon", 1.0),
            result("MONDO_1", "mondo", 0.9),
            result("NCIT_1", "ncit", 0.7));

        deduplicator.suppressWeakResults(results, soft("efo"));

        assertEquals(List.of("FOODON_1", "MONDO_1"), ids(results));
    }

    @Test
    void softPreferenceEndToEndKeepsPreferredOntologyBest() {
        var results = results(
            result("FOODON_1", "foodon", 1.0),
            result("EFO_1", "efo", 0.85),
            result("EFO_2", "efo", 0.82));

        var out = deduplicator.deduplicate(results, soft("efo"));

        assertEquals(List.of("FOODON_1", "EFO_1"), ids(out));
    }

    // ---- hard filter (targets set, includeOtherOntologies=false) ----

    @Test
    void hardFilterTightGapIsWithinEachOntology() {
        var results = results(
            result("EFO_1", "efo", 1.0),
            result("EFO_2", "efo", 0.97),
            result("EFO_3", "efo", 0.9),
            result("MONDO_1", "mondo", 0.6),
            result("MONDO_2", "mondo", 0.5));

        deduplicator.suppressWeakResults(results, hard("efo", "mondo"));

        // EFO_3 loses to EFO's own best by more than 0.05; MONDO_2 is below the
        // floor and below MONDO's own best; MONDO_1 is MONDO's best and exempt.
        assertEquals(List.of("EFO_1", "EFO_2", "MONDO_1"), ids(results));
    }

    @Test
    void hardFilterWeakRequestedOntologyBestSurvivesFloor() {
        // The example from the issue: EFO 1.0 and MONDO 0.6 with both requested.
        var results = results(
            result("EFO_1", "efo", 1.0),
            result("MONDO_1", "mondo", 0.6));

        deduplicator.suppressWeakResults(results, hard("efo", "mondo"));

        assertEquals(List.of("EFO_1", "MONDO_1"), ids(results));
    }

    @Test
    void hardFilterEndToEndReturnsBestPerRequestedOntology() {
        // MONDO is listed first: keepHigherPriorityOnTie (rule 7) would otherwise
        // still drop the lower-priority ontology's result in favour of the
        // higher-scoring EFO one, which is independent of this rule.
        var results = results(
            result("EFO_1", "efo", 1.0),
            result("EFO_2", "efo", 0.9),
            result("MONDO_1", "mondo", 0.6),
            result("MONDO_2", "mondo", 0.58));

        var out = deduplicator.deduplicate(results, hard("mondo", "efo"));

        assertEquals(List.of("EFO_1", "MONDO_1"), ids(out));
    }

    // ---- helpers ----

    private static Filter soft(String... targets) {
        return Filter.fromLists(null, null, List.of(targets), true);
    }

    private static Filter hard(String... targets) {
        return Filter.fromLists(null, null, List.of(targets), false);
    }

    private static List<MapResult> results(MapResult... results) {
        return new ArrayList<>(List.of(results));
    }

    private static MapResult result(String termId, String ontology, double confidence) {
        MapResult r = new MapResult();
        r.textToMap = "text";
        r.ontologyTermID = termId;
        r.ontologyURI = ontology;
        r.ontologyTermLabel = termId;
        r.mappingConfidence = confidence;
        return r;
    }

    private static List<String> ids(List<MapResult> results) {
        return results.stream().map(r -> r.ontologyTermID).toList();
    }
}
