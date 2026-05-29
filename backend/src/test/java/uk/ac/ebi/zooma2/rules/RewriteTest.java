package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RewriteTest {

    // Domain words/patterns live in the rules, not in code: the `normalize` action
    // carries its own regex, and only generic transforms (stripParentheticals,
    // collapseDuplicateTokens) are code primitives.
    static final String TRAIT = """
        {"id":"trait","domain":"trait","appliesWhen":{"propertyTypeIn":["disease","unspecified"]},
         "rules":[
           {"id":"strip-suffix","stage":"rewrite","priority":50,
            "when":{"queryContainsAnyToken":["self"]},
            "then":[{"normalize":{"pattern":"(?i) self reported","replacement":""}}]},
           {"id":"strip-parens","stage":"rewrite","priority":40,
            "when":{"queryContainsAnyToken":["qc"]},
            "then":[{"applyTransform":{"name":"stripParentheticals"}}]}
         ]}""";

    private RuleContext ctx(String text, String type) {
        return new RuleContext(text, type, List.of(), true, List.of(), List.of(), List.of());
    }

    @Test
    void normalizeActionStripsSuffix() {
        RuleEngine e = RuleSetLoader.loadFromJson(TRAIT);
        RuleContext c = ctx("asthma self reported", "disease");
        e.fire(RuleStage.REWRITE, c);
        assertEquals("asthma", c.effectiveText());
    }

    @Test
    void applyTransformStripsParentheticals() {
        RuleEngine e = RuleSetLoader.loadFromJson(TRAIT);
        RuleContext c = ctx("asthma (QC'd)", "disease");
        e.fire(RuleStage.REWRITE, c);
        assertEquals("asthma", c.effectiveText());
    }

    @Test
    void leavesCleanQueryUnchanged() {
        RuleEngine e = RuleSetLoader.loadFromJson(TRAIT);
        RuleContext c = ctx("type 2 diabetes mellitus", "disease");
        e.fire(RuleStage.REWRITE, c);
        assertEquals("type 2 diabetes mellitus", c.effectiveText());
    }

    @Test
    void addVariantPopulatesQueryVariants() {
        String rs = """
            {"id":"v","appliesWhen":{"propertyTypeIn":["*"]},
             "rules":[{"id":"variant","stage":"rewrite","priority":30,
                       "when":{"queryContainsAnyToken":["chronic"]},
                       "then":[{"addVariant":{"transform":"stripParentheticals"}}]}]}""";
        RuleEngine e = RuleSetLoader.loadFromJson(rs);
        RuleContext c = ctx("lung disease (chronic)", "disease");
        e.fire(RuleStage.REWRITE, c);
        assertEquals(List.of("lung disease"), c.queryVariants());
        // the effective (primary) text is left untouched by addVariant
        assertEquals("lung disease (chronic)", c.effectiveText());
    }

    @Test
    void addNormalizedVariantAddsLowercasedVariantLeavingPrimary() {
        String rs = """
            {"id":"v","appliesWhen":{"propertyTypeIn":["*"]},
             "rules":[{"id":"canon","stage":"rewrite","priority":20,
                       "then":[{"addNormalizedVariant":{"lowercaseFirst":true,
                                "subs":[{"pattern":"high density lipoprotein","replacement":"hdl"}]}}]}]}""";
        RuleEngine e = RuleSetLoader.loadFromJson(rs);
        RuleContext c = ctx("High Density Lipoprotein cholesterol", "disease");
        e.fire(RuleStage.REWRITE, c);
        assertEquals("High Density Lipoprotein cholesterol", c.effectiveText()); // primary untouched
        assertTrue(c.queryVariants().contains("hdl cholesterol"));              // normalized variant added
    }

    @Test
    void genericTransformsBehaveAsSpecified() {
        assertEquals("lung disease", NamedTransforms.apply("stripParentheticals", "lung disease (chronic)", null));
        assertEquals("heart disease", NamedTransforms.apply("collapseDuplicateTokens", "heart heart heart disease", null));
    }
}
