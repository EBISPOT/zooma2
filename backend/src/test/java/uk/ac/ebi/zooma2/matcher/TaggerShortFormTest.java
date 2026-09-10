package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.repo.OlsShortForms;

/** tag_text hits carry only an IRI; their ids must come out the way OLS renders them for that ontology. */
class TaggerShortFormTest {

    private static PrefixMap prefixMap;

    @BeforeAll
    static void load() {
        prefixMap = new PrefixMap();
    }

    /** OLS stub: canned tag_text hits and the ontology configuration OLS would apply. */
    private static class StubOlsRepo extends OlsClientRepo {
        @Override
        public Map<String, List<TagTextMatch>> tagText(List<String> terms, List<String> ontologyIds) {
            return Map.of("rat", List.of(
                new TagTextMatch("rat", "http://www.ebi.ac.uk/efo/EFO_0000400", "efo", 1.0, "LABEL", null, null, null, false),
                new TagTextMatch("rat", "http://id.nlm.nih.gov/mesh/D051381", "mesh", 1.0, "LABEL", null, null, null, false),
                new TagTextMatch("rat", "http://www.orpha.net/ORDO/Orphanet_224", "ordo", 1.0, "LABEL", null, null, null, false),
                new TagTextMatch("rat", "http://purl.obolibrary.org/obo/NCBITaxon_10116", "ncbitaxon", 1.0, "LABEL", null, null, null, false),
                new TagTextMatch("rat", "http://purl.obolibrary.org/obo/NCBITaxon_10116", "efo", 1.0, "synonym", null, null, null, false)
            ));
        }

        @Override
        public String olsShortForm(String ontologyId, String iri) {
            return switch (ontologyId) {
                case "efo" -> OlsShortForms.shortForm(ontologyId, "EFO", List.of("http://www.ebi.ac.uk/efo/EFO_"), iri);
                case "mesh" -> OlsShortForms.shortForm(ontologyId, "mesh", List.of("http://id.nlm.nih.gov/mesh/"), iri);
                case "ordo" -> OlsShortForms.shortForm(ontologyId, "ORDO", List.of("http://www.orpha.net/ORDO/Orphanet_"), iri);
                default -> OlsShortForms.shortForm(ontologyId, null, null, iri);
            };
        }
    }

    @Test
    void idsFollowOlsConventionPerOntology() {
        var matcher = new OlsTextTaggerMatcher(new StubOlsRepo(), prefixMap);
        var annotations = matcher.bulkTagText(List.of("rat")).get("rat");

        assertEquals("EFO_0000400", annotations.get(0).resolvedTerm.short_form);
        assertEquals("mesh_D051381", annotations.get(1).resolvedTerm.short_form);
        assertEquals("ORDO_224", annotations.get(2).resolvedTerm.short_form);
        assertEquals("NCBITaxon_10116", annotations.get(3).resolvedTerm.short_form);
        // An NCBITaxon term reported from EFO's file keeps its own namespace in the id
        assertEquals("NCBITaxon_10116", annotations.get(4).resolvedTerm.short_form);
        assertEquals("NCBITaxon_10116", OlsTextTaggerMatcher.namespaceIdOf(annotations.get(4)));
    }
}
