package uk.ac.ebi.zooma2.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/** Expansions must be scored by how similar the class is, with a cutoff (issue #19, point 2). */
class SimilarExpansionScoringTest {

    private static OlsTerm similar(String iri, String ontology, Double score) {
        OlsTerm t = new OlsTerm();
        t.iri = iri;
        t.label = iri;
        t.ontology_name = ontology;
        t.score = score;
        return t;
    }

    @Test
    void confidenceScalesWithSimilarityAndWeakOrScorelessClassesAreDropped() {
        String seedIri = "http://purl.obolibrary.org/obo/CHEBI_27899";
        OlsClientRepo stub = new OlsClientRepo() {
            @Override
            public List<OlsTerm> findSimilarTerms(String termIri, String model, int size) {
                return List.of(
                    similar("http://purl.obolibrary.org/obo/ECTO_1", "ecto", 0.95),
                    similar("http://purl.obolibrary.org/obo/ECTO_2", "ecto", 0.6),   // below cutoff
                    similar("http://purl.obolibrary.org/obo/ECTO_3", "ecto", null),  // no evidence
                    similar("http://purl.obolibrary.org/obo/CHEBI_9", "chebi", 0.99), // not a target
                    similar(seedIri, "ecto", 1.0));                                   // the seed itself
            }
        };
        Annotation seed = new Annotation();
        seed.annotatedProperty = new Annotation.AnnotatedProperty();
        seed.annotatedProperty.propertyValue = "cisplatin";
        seed.semanticTags = List.of(seedIri);
        seed.confidence = 1.0;
        seed.provenance = new Annotation.Provenance();
        seed.provenance.source = new Annotation.Source();
        seed.provenance.source.name = "chebi";
        seed.provenance.evidence = "OLS_TEXT_TAGGER";
        seed.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical("ols:chebi", "OLS_TEXT_TAGGER", "cisplatin", "cisplatin", seedIri, 1.0));

        MatchContext context = new MatchContext("cisplatin", null, Filter.fromLists(null, null, List.of("ecto"), false), "m")
            .withPreviousResults(List.of(seed));
        List<Annotation> out = new OlsEmbeddingSimilarMatcher(stub, null, 0.7).findMatches(context);

        assertEquals(1, out.size());
        Annotation expansion = out.get(0);
        assertEquals("http://purl.obolibrary.org/obo/ECTO_1", expansion.semanticTags.get(0));
        assertEquals(1.0 * 0.7 * 0.95, expansion.confidence, 1e-9);
        V3MappingProvenanceStepDto step = expansion.mappingProvenance.get(1);
        assertEquals("OLS_LLM_SIMILAR", step.matchType);
        assertEquals(0.95, step.similarity, 1e-9, "the real similarity, not null");
        assertEquals(expansion.confidence, step.confidence, 1e-9, "the real confidence, not a hard-coded 0.9");
    }
}
