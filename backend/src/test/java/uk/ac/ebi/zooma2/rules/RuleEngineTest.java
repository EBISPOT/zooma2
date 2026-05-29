package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.MapResult;

class RuleEngineTest {

    static final String COMMON = """
        { "id":"common-gate", "domain":"any", "appliesWhen":{"propertyTypeIn":["*"]},
          "rules":[
            {"id":"reject-pato","stage":"gate","priority":100,
             "when":{"candidateIdIn":["PATO_0000461"]},
             "then":[{"reject":{"reason":"normal"}}]}
          ]}""";

    static final String TRAIT = """
        { "id":"trait-default", "domain":"trait",
          "appliesWhen":{"propertyTypeIn":["disease","phenotype","unspecified"]},
          "includes":["common-gate"],
          "rules":[
            {"id":"reject-nondisease","stage":"gate","priority":80,
             "when":{"allOf":[{"propertyTypeIn":["disease","phenotype"]},
                              {"candidateOntologyIn":["uberon","cl"]}]},
             "then":[{"reject":{"reason":"nondisease"}}]}
          ]}""";

    static final String ANALYTE = """
        { "id":"analyte-default", "domain":"analyte",
          "appliesWhen":{"propertyTypeIn":["measurement","protein"]},
          "includes":["common-gate"], "rules":[] }""";

    private RuleContext ctx(String type) {
        return new RuleContext("q", type, List.of(), true, List.of(), List.of(), List.of());
    }

    private MapResult term(String id) {
        MapResult r = new MapResult();
        r.ontologyTermID = id;
        r.ontologyTermLabel = id;
        return r;
    }

    @Test
    void emptyEngineNeverRejects() {
        assertFalse(RuleEngine.empty().shouldReject(ctx("disease"), term("UBERON_1")));
    }

    @Test
    void traitRejectsNonDiseaseEntityButKeepsEfo() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT, ANALYTE);
        assertTrue(e.shouldReject(ctx("disease"), term("UBERON_0002048")));
        assertFalse(e.shouldReject(ctx("disease"), term("EFO_0000270")));
    }

    @Test
    void includedRulesetAppliesToParentDomain() {
        // trait-default includes common-gate → PATO 'normal' rejected for a disease query
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT);
        assertTrue(e.shouldReject(ctx("disease"), term("PATO_0000461")));
    }

    @Test
    void appliesWhenScopesRulesByPropertyType() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT, ANALYTE);
        // analyte query: trait-default is NOT active, so UBERON is not rejected by the trait rule
        assertFalse(e.shouldReject(ctx("protein"), term("UBERON_0002048")));
        // but common-gate (wildcard) IS active, so PATO is still rejected
        assertTrue(e.shouldReject(ctx("protein"), term("PATO_0000461")));
    }

    @Test
    void recordsFiredRuleForAudit() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT);
        RuleContext rc = ctx("disease");
        e.shouldReject(rc, term("UBERON_0002048"));
        assertFalse(rc.firedRules().isEmpty());
        assertEquals("reject-nondisease", rc.firedRules().get(rc.firedRules().size() - 1).ruleId());
    }

    @Test
    void unknownConditionKeyFailsLoudly() {
        String bad = """
            {"id":"x","appliesWhen":{"propertyTypeIn":["*"]},
             "rules":[{"id":"r","stage":"gate","when":{"bogusKey":true},"then":[]}]}""";
        assertThrows(RuntimeException.class, () -> RuleSetLoader.loadFromJson(bad));
    }
}
