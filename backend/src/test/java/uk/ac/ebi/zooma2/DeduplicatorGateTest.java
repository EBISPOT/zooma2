package uk.ac.ebi.zooma2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.rules.RuleContext;
import uk.ac.ebi.zooma2.rules.RuleSetLoader;

/** End-to-end check that the GATE stage drops rejected candidates inside Deduplicator. */
class DeduplicatorGateTest {

    static final String COMMON = """
        {"id":"common-gate","domain":"any","appliesWhen":{"propertyTypeIn":["*"]},
         "rules":[{"id":"reject-pato","stage":"gate","priority":100,
                   "when":{"candidateIdIn":["PATO_0000461"]},
                   "then":[{"reject":{"reason":"normal"}}]}]}""";

    static final String TRAIT = """
        {"id":"trait-default","domain":"trait",
         "appliesWhen":{"propertyTypeIn":["disease","phenotype","unspecified"]},
         "includes":["common-gate"],
         "rules":[{"id":"reject-nondisease","stage":"gate","priority":80,
                   "when":{"allOf":[{"propertyTypeIn":["disease","phenotype"]},
                                    {"candidateOntologyIn":["uberon"]}]},
                   "then":[{"reject":{"reason":"nondisease"}}]}]}""";

    private MapResult lexical(String id, String label, double conf) {
        MapResult r = new MapResult();
        r.ontologyTermID = id;
        r.ontologyTermLabel = label;
        r.ontologyURI = id.split("_")[0].toLowerCase();
        r.mappingConfidence = conf;
        r.mappingProvenance = new ArrayList<>(List.of(
            V3MappingProvenanceStepDto.lexical("ols", "exact_label", label, label, id, conf)));
        return r;
    }

    private Filter noOntologyFilter() {
        // includeOtherOntologies=true with no target ontologies → no ontology filtering,
        // so survival is governed purely by the GATE rules.
        return Filter.fromLists(List.of(), List.of(), List.of(), true);
    }

    private RuleContext ctx(String text, String type) {
        return new RuleContext(text, type, List.of(), true, List.of(), List.of(), List.of());
    }

    @Test
    void gateRemovesNonDiseaseEntityButKeepsDisease() {
        Deduplicator dedup = new Deduplicator(new PrefixMap(), RuleSetLoader.loadFromJson(COMMON, TRAIT));
        List<MapResult> results = new ArrayList<>(List.of(
            lexical("EFO_0000270", "asthma", 0.9),
            lexical("UBERON_0002048", "lung", 0.9)));

        List<String> ids = dedup.deduplicate(results, noOntologyFilter(), null, ctx("asthma", "disease"))
            .stream().map(r -> r.ontologyTermID).toList();

        assertTrue(ids.contains("EFO_0000270"), "EFO disease term should survive");
        assertFalse(ids.contains("UBERON_0002048"), "UBERON anatomy term should be gated out");
    }

    @Test
    void emptyEngineLeavesResultsUnchanged() {
        Deduplicator dedup = new Deduplicator(new PrefixMap()); // no rules → no GATE
        List<MapResult> results = new ArrayList<>(List.of(
            lexical("EFO_0000270", "asthma", 0.9),
            lexical("UBERON_0002048", "lung", 0.9)));

        List<String> ids = dedup.deduplicate(results, noOntologyFilter(), null, ctx("asthma", "disease"))
            .stream().map(r -> r.ontologyTermID).toList();

        assertTrue(ids.contains("EFO_0000270"));
        assertTrue(ids.contains("UBERON_0002048"));
    }
}
