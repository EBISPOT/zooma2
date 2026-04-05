package uk.ac.ebi.zooma2;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.matcher.OlsTextTaggerMatcher;
import uk.ac.ebi.zooma2.search.AnnotationEngine;
import uk.ac.ebi.zooma2.mapping.StringMapper;
import uk.ac.ebi.zooma2.mapping.BatchMapper;

public class ZoomaAnnotator {

    OlsClientRepo olsRepo;
    PrefixMap prefixMap = new PrefixMap();
    Deduplicator deduplicator = new Deduplicator(prefixMap);
    OlsTextTaggerMatcher textTaggerService;
    AnnotationEngine annotationEngine;
    StringMapper stringMapper;
    BatchMapper batchMapper;

    public ZoomaAnnotator(OlsClientRepo olsRepo) {
        this.olsRepo = olsRepo;
        this.textTaggerService = new OlsTextTaggerMatcher(olsRepo, prefixMap);
        this.annotationEngine = new AnnotationEngine(olsRepo);
        this.stringMapper = new StringMapper(annotationEngine, olsRepo, prefixMap);
        this.batchMapper = new BatchMapper(stringMapper, textTaggerService, deduplicator);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources) {
        return batchMapper.mapAll(stringsToMap, sources);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model) {
        return batchMapper.mapAll(stringsToMap, sources, model);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model, List<String> excludeTermIds) {
        return batchMapper.mapAll(stringsToMap, sources, model, excludeTermIds);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model, List<String> excludeTermIds, boolean returnAll) {
        return batchMapper.mapAll(stringsToMap, sources, model, excludeTermIds, returnAll);
    }

    public Collection<MapResult> mapAll(Stream<StringToMap> stringsToMap, Filter sources, String model, List<String> excludeTermIds, boolean returnAll, Boolean deep) {
        return batchMapper.mapAll(stringsToMap, sources, model, excludeTermIds, returnAll, deep);
    }

    public void mapEach(List<StringToMap> properties, Filter filter, String model,
                        java.util.function.BiConsumer<StringToMap, List<MapResult>> onPropertyMapped) {
        batchMapper.mapEach(properties, filter, model, onPropertyMapped);
    }

    public List<MapResult> mapOne(StringToMap s, Filter sources, String model, Boolean deep) {
        return stringMapper.mapOne(s, sources, model, deep);
    }

    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources) {
        return annotationEngine.annotate(stringToMap, type, sources);
    }

    public Stream<Annotation> annotate(String stringToMap, String type, Filter sources, String model, Boolean deep) {
        return annotationEngine.annotate(stringToMap, type, sources, model, deep);
    }



    public List<Map<String, Object>> getEmbeddingModels() {
        return olsRepo.getEmbeddingModels();
    }

    /**
     * Run OLS tag_text on a whole body of text and return the raw matches with character offsets.
     */
    public List<OlsClientRepo.WholeTextTagMatch> tagWholeText(String text, List<String> ontologyIds) {
        return olsRepo.tagWholeText(text, ontologyIds);
    }

    boolean isNone(List<String> list) {
        return textTaggerService.isNone(list);
    }

    
}
