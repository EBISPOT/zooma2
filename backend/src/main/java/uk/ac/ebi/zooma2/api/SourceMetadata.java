package uk.ac.ebi.zooma2.api;

import uk.ac.ebi.zooma2.repo.OlsOntology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SourceMetadata {

    private static final List<DatabaseSource> LEGACY_DATABASE_SOURCES = List.of(
        new DatabaseSource("sysmicro", "https://www.ebi.ac.uk/fg/sym"),
        new DatabaseSource("atlas", "https://www.ebi.ac.uk/gxa"),
        new DatabaseSource("ebisc", "https://cells.ebisc.org/"),
        new DatabaseSource("cttv", "https://www.targetvalidation.org"),
        new DatabaseSource("uniprot", "https://www.ebi.ac.uk/uniprot"),
        new DatabaseSource("eva-clinvar", "https://www.ebi.ac.uk/eva"),
        new DatabaseSource("cbi", "https://www.ebi.ac.uk/biosamples"),
        new DatabaseSource("clinvar-xrefs", "https://www.ncbi.nlm.nih.gov/clinvar"),
        new DatabaseSource("metabolights", "https://www.ebi.ac.uk/metabolights"),
        new DatabaseSource("ukbiobank", "https://github.com/EBISPOT/EFO-UKB-mappings"),
        new DatabaseSource("EBI-BioSamples", "https://www.ebi.ac.uk/biosamples/"),
        new DatabaseSource("FAANG", "https://www.ebi.ac.uk/vg/faang"),
        new DatabaseSource("HCA", "https://www.ebi.ac.uk/about/collaborations/human-cell-atlas"),
        new DatabaseSource("GWAS", "http://www.ebi.ac.uk/gwas")
    );

    private SourceMetadata() {
    }

    public static List<Map<String, Object>> buildSources(List<OlsOntology> ontologies) {
        var sources = new ArrayList<Map<String, Object>>();
        LEGACY_DATABASE_SOURCES.stream()
            .map(DatabaseSource::toMap)
            .forEach(sources::add);
        ontologies.stream()
            .map(SourceMetadata::ontologyToMap)
            .forEach(sources::add);
        return sources;
    }

    public static List<Map<String, Object>> buildSources(List<String> curationSources, List<OlsOntology> ontologies) {
        Set<String> available = curationSources.stream()
            .map(SourceMetadata::sourceKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        var sources = new ArrayList<Map<String, Object>>();
        var handled = new LinkedHashSet<String>();

        for (var databaseSource : LEGACY_DATABASE_SOURCES) {
            String key = sourceKey(databaseSource.name);
            if (available.contains(key)) {
                sources.add(databaseSource.toMap());
                handled.add(key);
            }
        }

        curationSources.stream()
            .filter(source -> !handled.contains(sourceKey(source)))
            .sorted(Comparator.comparing(SourceMetadata::sourceKey))
            .map(source -> new DatabaseSource(source, source).toMap())
            .forEach(sources::add);

        ontologies.stream()
            .map(SourceMetadata::ontologyToMap)
            .forEach(sources::add);

        return sources;
    }

    public static String sourceUri(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return LEGACY_DATABASE_SOURCES.stream()
            .filter(source -> sourceKey(source.name).equals(sourceKey(name)))
            .map(source -> source.uri)
            .findFirst()
            .orElse(name);
    }

    private static String sourceKey(String source) {
        return source == null ? "" : source.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> ontologyToMap(OlsOntology ontology) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "ONTOLOGY");
        map.put("name", ontology.ontologyId);
        map.put("title", ontology.config.title != null ? ontology.config.title : "");
        map.put("description", ontology.config.description != null ? ontology.config.description : "");
        map.put("uri", ontology.ontologyId);
        return map;
    }

    private static final class DatabaseSource {
        private final String name;
        private final String uri;

        private DatabaseSource(String name, String uri) {
            this.name = name;
            this.uri = uri;
        }

        private Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("type", "DATABASE");
            map.put("name", name);
            map.put("uri", uri);
            return map;
        }
    }
}
