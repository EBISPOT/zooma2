package uk.ac.ebi.zooma2.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.Bioregistry;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/** One bad property must not fail the whole non-streaming request (issue #23 item 7, done with #18's rewrite of mapAll). */
class MapAllContainmentTest {

    private static final PrefixMap PREFIX_MAP = new PrefixMap(Bioregistry.fromSnapshot());

    /** No tagger hits, never short-circuits. */
    private static class NoTagger extends OlsTextTaggerMatcher {
        NoTagger() { super(new OlsClientRepo(), PREFIX_MAP); }
        @Override public TaggerResults bulkTag(List<String> terms) { return new TaggerResults(Map.of(), java.util.Set.of(), null); }
    }

    /** Maps every text to one term, except "boom", which blows up. */
    private static class ExplodingMapper extends StringMapper {
        ExplodingMapper() { super(null, new OlsClientRepo(), PREFIX_MAP); }
        @Override
        public List<MapResult> map(StringToMap s, List<Annotation> tagger, Filter filter, String model, Boolean deep, List<String> excludeTermIds) {
            if ("boom".equals(s.textToMap)) throw new IllegalStateException("matcher exploded");
            MapResult r = new MapResult();
            r.textToMap = s.textToMap;
            r.propertyType = s.propertyType;
            r.ontologyTermID = "EFO_" + s.textToMap.hashCode();
            r.ontologyTermIri = "http://www.ebi.ac.uk/efo/" + r.ontologyTermID;
            r.ontologyURI = "efo";
            r.mappingConfidence = 1.0;
            r.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical("ols:efo", "OLS_TEXT_TAGGER", s.textToMap, s.textToMap, r.ontologyTermIri, 1.0));
            return new ArrayList<>(List.of(r));
        }
    }

    private static StringToMap property(String text) {
        StringToMap s = new StringToMap();
        s.textToMap = text;
        return s;
    }

    @Test
    void aFailingPropertyYieldsAnErrorResultAndTheOthersMapNormally() {
        BatchMapper batch = new BatchMapper(new ExplodingMapper(), new NoTagger(), new Deduplicator(PREFIX_MAP));
        List<StringToMap> props = List.of(property("liver"), property("boom"), property("kidney"));

        List<MapResult> results = new ArrayList<>(batch.mapAll(props.stream(), Filter.fromLists(null, null, null, true), "m", null, false, null));

        assertEquals(3, results.size());
        assertEquals(List.of("liver", "boom", "kidney"), results.stream().map(r -> r.textToMap).toList(), "input order kept");
        assertNull(results.get(0).error);
        assertTrue(results.get(1).error.startsWith("IllegalStateException: matcher exploded"), results.get(1).error);
        assertNull(results.get(2).error);
    }
}
