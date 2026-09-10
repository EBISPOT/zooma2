package uk.ac.ebi.zooma2.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.api.v3.dto.V3PropertyMappingDto;
import uk.ac.ebi.zooma2.matcher.MatchContext;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.Bioregistry;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.search.AnnotationEngine;
import uk.ac.ebi.zooma2.util.Diagnostics;

/** An OLS outage must produce a visibly degraded answer, not a clean empty one (issue #15, point 2). */
class WarningsSurfaceTest {

    private static final PrefixMap PREFIX_MAP = new PrefixMap(Bioregistry.fromSnapshot());

    /** Engine whose matchers "fail": they report the outage the way the OLS client does and find nothing. */
    private static class OutageEngine extends AnnotationEngine {
        OutageEngine() { super(new OlsClientRepo()); }
        @Override public List<Annotation> annotateShallow(MatchContext c) {
            Diagnostics.warn("OLS lexical search unavailable: Connection refused");
            Diagnostics.warn("OLS embedding search unavailable: Connection refused");
            Diagnostics.warn("OLS lexical search unavailable: Connection refused"); // duplicates collapse
            return new ArrayList<>();
        }
        @Override public List<Annotation> annotateDeep(MatchContext c, List<Annotation> s) {
            Diagnostics.warn("OLS embedding search unavailable: Connection refused");
            return new ArrayList<>();
        }
    }

    private static StringToMap property(String text) {
        StringToMap s = new StringToMap();
        s.textToMap = text;
        return s;
    }

    @Test
    void matcherOutagesBecomeWarningsOnThePropertyNotSilentEmptiness() {
        StringMapper mapper = new StringMapper(new OutageEngine(), new OlsClientRepo(), PREFIX_MAP);
        List<MapResult> results = mapper.map(property("diabetes"), List.of(), Filter.fromLists(null, null, List.of("efo"), false), "m", null, null);

        List<String> warnings = results.stream().filter(r -> r.warning != null).map(r -> r.warning).toList();
        assertEquals(List.of("OLS lexical search unavailable: Connection refused", "OLS embedding search unavailable: Connection refused"), warnings);
        assertTrue(results.stream().allMatch(MapResult::isDiagnostic), "no candidates, only diagnostics");
        assertNull(Diagnostics.current(), "the sink is closed afterwards");

        // Deduplication keeps them; the V3 property reports them and lists no candidates
        List<MapResult> deduped = new Deduplicator(PREFIX_MAP).deduplicate(new ArrayList<>(results), Filter.fromLists(null, null, List.of("efo"), false), null);
        V3PropertyMappingDto dto = V3PropertyMappingDto.fromResults(null, "diabetes", deduped);
        assertEquals(0, dto.candidates.size());
        assertNull(dto.error);
        assertEquals(warnings, dto.warnings);
        assertNull(V3PropertyMappingDto.fromResults(null, "x", List.of()).warnings, "absent when clean");
    }

    @Test
    void aFailedTaggerChunkWarnsExactlyItsOwnProperties() {
        OlsTextTaggerMatcher failingTagger = new OlsTextTaggerMatcher(new OlsClientRepo(), PREFIX_MAP) {
            @Override public TaggerResults bulkTag(List<String> terms) {
                return new TaggerResults(Map.of(), java.util.Set.of("kidney"), "HTTP 503 for POST tag_text");
            }
        };
        StringMapper quietMapper = new StringMapper(new AnnotationEngine(new OlsClientRepo()) {
            @Override public List<Annotation> annotateShallow(MatchContext c) { return new ArrayList<>(); }
        }, new OlsClientRepo(), PREFIX_MAP);
        BatchMapper batch = new BatchMapper(quietMapper, failingTagger, new Deduplicator(PREFIX_MAP));

        List<MapResult> all = new ArrayList<>(batch.mapAll(List.of(property("liver"), property("kidney")).stream(), Filter.fromLists(null, null, null, true), "m", null, false, false));
        List<MapResult> kidney = all.stream().filter(r -> "kidney".equals(r.textToMap)).toList();
        List<MapResult> liver = all.stream().filter(r -> "liver".equals(r.textToMap)).toList();
        assertEquals(1, kidney.size());
        assertTrue(kidney.get(0).warning.startsWith("OLS text tagger unavailable: HTTP 503"), kidney.get(0).warning);
        assertTrue(liver.stream().noneMatch(r -> r.warning != null), "the other property is untouched");
    }
}
