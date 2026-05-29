package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.MapResult;

/** Per-request ruleset selection: which rulesets apply is chosen at query time. */
class RuleSetSelectionTest {

    static final String COMMON = """
        {"id":"common-gate","appliesWhen":{"propertyTypeIn":["*"]},
         "rules":[{"id":"reject-pato","stage":"gate","priority":100,
                   "when":{"candidateIdIn":["PATO_0000461"]},
                   "then":[{"reject":{"reason":"normal"}}]}]}""";

    static final String TRAIT = """
        {"id":"trait-default","appliesWhen":{"propertyTypeIn":["disease","unspecified"]},
         "includes":["common-gate"],
         "rules":[{"id":"reject-uberon","stage":"gate","priority":80,
                   "when":{"candidateOntologyIn":["uberon"]},
                   "then":[{"reject":{"reason":"anatomy"}}]}]}""";

    static final String ANALYTE = """
        {"id":"analyte-default","appliesWhen":{"propertyTypeIn":["*"]},
         "rules":[{"id":"reject-foo","stage":"gate","priority":50,
                   "when":{"candidateIdIn":["FOO_1"]},
                   "then":[{"reject":{"reason":"foo"}}]}]}""";

    // GWAS Catalog profile: opt-in (default:false), bundles common-gate + trait-default.
    static final String GWAS = """
        {"id":"gwas-catalog","default":false,"appliesWhen":{"propertyTypeIn":["*"]},
         "includes":["common-gate","trait-default"]}""";

    private RuleContext ctx(String type, List<String> selected) {
        RuleContext c = new RuleContext("q", type, List.of(), true, List.of(), List.of(), List.of());
        c.setSelectedRuleSets(selected);
        return c;
    }

    private MapResult term(String id) {
        MapResult r = new MapResult();
        r.ontologyTermID = id;
        r.ontologyTermLabel = id;
        return r;
    }

    @Test
    void noSelectionAppliesAllDefaultRulesets() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT, ANALYTE, GWAS);
        RuleContext c = ctx("disease", null);
        assertTrue(e.shouldReject(c, term("PATO_0000461"))); // common-gate
        assertTrue(e.shouldReject(c, term("UBERON_1")));      // trait-default
        assertTrue(e.shouldReject(c, term("FOO_1")));         // analyte-default
    }

    @Test
    void selectionIsExclusiveButPullsIncludes() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT, ANALYTE, GWAS);
        RuleContext c = ctx("disease", List.of("gwas-catalog"));
        assertTrue(e.shouldReject(c, term("PATO_0000461"))); // common-gate (included by gwas-catalog)
        assertTrue(e.shouldReject(c, term("UBERON_1")));     // trait-default (included by gwas-catalog)
        assertFalse(e.shouldReject(c, term("FOO_1")));       // analyte-default NOT selected → inactive
    }

    @Test
    void optInProfileAppliesOnlyWhenSelected() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT, ANALYTE, GWAS);
        // selecting only common-gate does NOT bring trait-default's anatomy gate
        assertFalse(e.shouldReject(ctx("disease", List.of("common-gate")), term("UBERON_1")));
        // selecting gwas-catalog DOES (it includes trait-default)
        assertTrue(e.shouldReject(ctx("disease", List.of("gwas-catalog")), term("UBERON_1")));
    }

    @Test
    void selectionIsCaseInsensitive() {
        RuleEngine e = RuleSetLoader.loadFromJson(COMMON, TRAIT, ANALYTE, GWAS);
        assertTrue(e.shouldReject(ctx("disease", List.of("GWAS-Catalog")), term("UBERON_1")));
    }
}
