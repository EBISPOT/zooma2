package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class OverrideTest {

    static final String RS = """
        {"id":"phrase","domain":"trait","appliesWhen":{"propertyTypeIn":["disease","unspecified"]},
         "rules":[
           {"id":"map-t2d","stage":"override","priority":100,
            "when":{"queryMatches":"(?i)^type 2 diabetes$"},
            "then":[{"emitTerm":{"id":"EFO_0001360","label":"type 2 diabetes mellitus","confidence":0.99}}]}
         ]}""";

    private RuleContext ctx(String text, String type) {
        return new RuleContext(text, type, List.of(), true, List.of(), List.of(), List.of());
    }

    @Test
    void emitsCuratedTermOnPhraseMatch() {
        RuleEngine e = RuleSetLoader.loadFromJson(RS);
        RuleContext.EmittedTerm t = e.overrideTerm(ctx("type 2 diabetes", "disease"));
        assertNotNull(t);
        assertEquals("EFO_0001360", t.id());
        assertEquals(0.99, t.confidence(), 1e-9);
    }

    @Test
    void noOverrideWhenPhraseDoesNotMatch() {
        RuleEngine e = RuleSetLoader.loadFromJson(RS);
        assertNull(e.overrideTerm(ctx("asthma", "disease")));
    }

    @Test
    void noOverrideWhenDomainInactive() {
        RuleEngine e = RuleSetLoader.loadFromJson(RS);
        assertNull(e.overrideTerm(ctx("type 2 diabetes", "measurement")));
    }
}
