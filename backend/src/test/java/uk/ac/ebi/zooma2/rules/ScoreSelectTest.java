package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.MapResult;

class ScoreSelectTest {

    static final String ANALYTE = """
        {"id":"analyte","domain":"analyte","appliesWhen":{"propertyTypeIn":["measurement","protein"]},
         "rules":[
           {"id":"prefer-measurement","stage":"score","priority":10,
            "when":{"candidateLabelContainsAny":["measurement","level","concentration"]},
            "then":[{"boost":{"amount":0.015}}]},
           {"id":"prefer-efo","stage":"select","priority":10,
            "when":{"propertyTypeIn":["measurement","protein"]},
            "then":[{"preferOntologyOrder":{"list":["efo","oba"]}}]}
         ]}""";

    private RuleContext ctx(String type) {
        return new RuleContext("q", type, List.of(), true, List.of(), List.of(), List.of());
    }

    private MapResult term(String id, String label) {
        MapResult r = new MapResult();
        r.ontologyTermID = id;
        r.ontologyTermLabel = label;
        return r;
    }

    @Test
    void measurementKeywordEarnsScoreBonus() {
        RuleEngine e = RuleSetLoader.loadFromJson(ANALYTE);
        assertEquals(0.015, e.scoreDelta(ctx("measurement"), term("EFO_1", "insulin measurement")), 1e-9);
        assertEquals(0.0, e.scoreDelta(ctx("measurement"), term("EFO_2", "insulin")), 1e-9);
    }

    @Test
    void scoreOnlyAppliesToActiveDomain() {
        RuleEngine e = RuleSetLoader.loadFromJson(ANALYTE);
        // disease query: analyte ruleset is not active → no bonus
        assertEquals(0.0, e.scoreDelta(ctx("disease"), term("EFO_1", "insulin measurement")), 1e-9);
    }

    @Test
    void selectReturnsPreferredOntologyOrderWhenActive() {
        RuleEngine e = RuleSetLoader.loadFromJson(ANALYTE);
        assertEquals(List.of("efo", "oba"), e.selectOntologyOrder(ctx("protein")));
        assertNull(e.selectOntologyOrder(ctx("disease")));
    }
}
