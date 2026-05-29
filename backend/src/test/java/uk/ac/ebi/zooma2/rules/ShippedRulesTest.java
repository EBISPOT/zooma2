package uk.ac.ebi.zooma2.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.MapResult;

/**
 * Guards the actual shipped rule files in {@code backend/rules/}: they must parse
 * (no JSON typos / unknown keys) and behave. Skipped if the rules directory is not
 * on the test working directory.
 */
class ShippedRulesTest {

    private RuleContext disease(String text) {
        return new RuleContext(text, "disease", List.of(), true, List.of(), List.of(), List.of());
    }

    private MapResult term(String id, String label) {
        MapResult r = new MapResult();
        r.ontologyTermID = id;
        r.ontologyTermLabel = label;
        return r;
    }

    @Test
    void shippedRulesParseAndApply() {
        // Tests run with cwd = backend/, so the repo-root rules/ dir is ../rules.
        Path dir = Files.isDirectory(Paths.get("../rules")) ? Paths.get("../rules") : Paths.get("rules");
        Assumptions.assumeTrue(Files.isDirectory(dir), "repo-root rules/ present");

        RuleEngine e = RuleSetLoader.loadFrom(dir);
        assertTrue(e.ruleSetCount() >= 6, "expected trait-default, analyte-default, gwas-catalog, trait-canonical, analyte-lipid, hdruk");

        // trait-default rejects the forbidden id PATO_0000461 (also caught by the allowlist)
        assertTrue(e.shouldReject(disease("x"), term("PATO_0000461", "normal")));
        // trait output allowlist rejects a non-EFO/MONDO/HP/OBA term (UBERON anatomy)
        assertTrue(e.shouldReject(disease("lung"), term("UBERON_0002048", "lung")));
        // trait SELECT declares the clinical ontology preference (TRAIT_PREFERRED_PREFIX_ORDER)
        assertTrue(e.selectOntologyOrder(disease("asthma")).contains("efo"));

        // trait REWRITE strips self-report noise (TRAIT_QUERY_NOISE_SUBS[0])
        RuleContext rw = disease("self report - asthma");
        e.fire(RuleStage.REWRITE, rw);
        assertEquals("asthma", rw.effectiveText());

        // trait-canonical adds the semantic-normalized variant (TRAIT_CANONICAL_SIMILARITY_SUBS)
        RuleContext hdl = disease("High Density Lipoprotein");
        e.fire(RuleStage.REWRITE, hdl);
        assertTrue(hdl.queryVariants().contains("hdl"));

        // the opt-in gwas-catalog profile is selectable and pulls in the trait gate
        RuleContext gwasSel = disease("liver");
        gwasSel.setSelectedRuleSets(List.of("gwas-catalog"));
        assertTrue(e.shouldReject(gwasSel, term("UBERON_0002107", "liver")));
    }
}
