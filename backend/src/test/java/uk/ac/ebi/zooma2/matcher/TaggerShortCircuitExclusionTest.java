package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/** "Try again": an excluded term must not short-circuit the search (issue #14, point 4). */
class TaggerShortCircuitExclusionTest {

    private static OlsTextTaggerMatcher matcher;

    @BeforeAll
    static void init() {
        matcher = new OlsTextTaggerMatcher(new OlsClientRepo(), new PrefixMap()); // Bioregistry fetched once
    }

    private static Annotation fullMatch(String shortForm, String iri, String ontology) {
        Annotation a = new Annotation();
        a.annotatedProperty = new Annotation.AnnotatedProperty();
        a.annotatedProperty.propertyValue = "cisplatin";
        a.semanticTags = List.of(iri);
        a.confidence = 1.0;
        a.provenance = new Annotation.Provenance();
        a.provenance.source = new Annotation.Source();
        a.provenance.source.name = ontology;
        a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical("ols:" + ontology, "OLS_TEXT_TAGGER", "cisplatin", "cisplatin", iri, 1.0));
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.short_form = shortForm;
        t.ontology_name = ontology;
        a.resolvedTerm = t;
        return a;
    }

    private static final Annotation CHEBI = fullMatch("CHEBI_27899", "http://purl.obolibrary.org/obo/CHEBI_27899", "chebi");
    private static final Annotation NCIT = fullMatch("NCIT_C376", "http://purl.obolibrary.org/obo/NCIT_C376", "ncit");
    private static final Filter CHEBI_ONLY = Filter.fromLists(null, null, List.of("chebi"), false);

    @Test
    void shortCircuitsWithoutExclusions() {
        assertTrue(matcher.hasFullMatchFromTargetOntologies(List.of(CHEBI, NCIT), CHEBI_ONLY));
        assertTrue(matcher.hasFullMatchFromTargetOntologies(List.of(CHEBI, NCIT), CHEBI_ONLY, List.of()));
    }

    @Test
    void doesNotShortCircuitWhenTheOnlyDefinitiveMatchIsExcluded() {
        for (String excluded : new String[] {"CHEBI_27899", "chebi_27899", "CHEBI:27899", "http://purl.obolibrary.org/obo/CHEBI_27899"}) {
            assertFalse(matcher.hasFullMatchFromTargetOntologies(List.of(CHEBI, NCIT), CHEBI_ONLY, List.of(excluded)), excluded);
            assertFalse(matcher.hasFullMatchFromTargetOntologies(List.of(CHEBI), null, List.of(excluded)), excluded + " (no targets)");
        }
    }

    @Test
    void stillShortCircuitsWhenAnotherDefinitiveMatchRemains() {
        assertTrue(matcher.hasFullMatchFromTargetOntologies(List.of(CHEBI, NCIT), null, List.of("CHEBI_27899")));
        assertTrue(matcher.hasFullMatchFromTargetOntologies(List.of(CHEBI, NCIT), CHEBI_ONLY, List.of("NCIT_C376")));
    }
}
