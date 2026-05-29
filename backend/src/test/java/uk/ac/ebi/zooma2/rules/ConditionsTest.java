package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.MapResult;

class ConditionsTest {

    private RuleContext ctx(String text, String propertyType) {
        return new RuleContext(text, propertyType, List.of(), true, List.of(), List.of(), List.of());
    }

    private MapResult candidate(String id, String label) {
        MapResult r = new MapResult();
        r.ontologyTermID = id;
        r.ontologyTermLabel = label;
        return r;
    }

    @Test
    void propertyTypeInMatchesAndWildcard() {
        RuleContext c = ctx("asthma", "disease");
        assertTrue(new Conditions.PropertyTypeIn(List.of("disease", "phenotype")).matches(c));
        assertFalse(new Conditions.PropertyTypeIn(List.of("measurement")).matches(c));
        assertTrue(new Conditions.PropertyTypeIn(List.of("*")).matches(c));
    }

    @Test
    void propertyTypeNullBecomesUnspecified() {
        assertTrue(new Conditions.PropertyTypeIn(List.of("unspecified")).matches(ctx("x", null)));
    }

    @Test
    void candidateOntologyInParsesPrefixFromUnderscoreId() {
        RuleContext c = ctx("lung", "disease");
        c.beginCandidate(candidate("UBERON_0002048", "lung"));
        assertTrue(new Conditions.CandidateOntologyIn(List.of("uberon", "cl")).matches(c));
        c.beginCandidate(candidate("EFO_0000270", "asthma"));
        assertFalse(new Conditions.CandidateOntologyIn(List.of("uberon")).matches(c));
    }

    @Test
    void candidateIdInNormalisesColonAndCase() {
        RuleContext c = ctx("x", "disease");
        c.beginCandidate(candidate("PATO_0000461", "normal"));
        // rule written with a colon and lower-case still matches an underscore upper-case id
        assertTrue(new Conditions.CandidateIdIn(List.of("pato:0000461")).matches(c));
    }

    @Test
    void candidateConditionsAreNullSafeWithoutCandidate() {
        RuleContext c = ctx("x", "disease"); // no beginCandidate
        assertFalse(new Conditions.CandidateOntologyIn(List.of("uberon")).matches(c));
        assertFalse(new Conditions.CandidateIdIn(List.of("PATO_0000461")).matches(c));
        assertFalse(new Conditions.CandidateLabelMatches("(?i)normal").matches(c));
    }

    @Test
    void logicalComposition() {
        RuleContext c = ctx("asthma", "disease");
        c.beginCandidate(candidate("UBERON_0002048", "lung"));
        Condition all = new Conditions.AllOf(List.of(
            new Conditions.PropertyTypeIn(List.of("disease")),
            new Conditions.CandidateOntologyIn(List.of("uberon"))));
        assertTrue(all.matches(c));
        assertTrue(new Conditions.Not(new Conditions.CandidateOntologyIn(List.of("efo"))).matches(c));
        assertTrue(new Conditions.AnyOf(List.of(
            new Conditions.CandidateOntologyIn(List.of("efo")),
            new Conditions.CandidateOntologyIn(List.of("uberon")))).matches(c));
    }
}
