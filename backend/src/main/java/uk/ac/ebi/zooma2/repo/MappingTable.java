
package uk.ac.ebi.zooma2.repo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import uk.ac.ebi.zooma2.util.NormaliseString;

public class MappingTable {

    Map<String, Set<MappingTableEntry>> entries = new HashMap<>();

    public MappingTable() {
    }

    public void loadFromInputStream(InputStream inputStream, String databaseId, String databaseUrl) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Input stream is empty");
            }
            String[] headers = headerLine.split("\t");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split("\t");

                MappingTableEntry entry = new MappingTableEntry();
                entry.databaseId = databaseId;
                entry.databaseId = databaseUrl;

                for (int i = 0; i < headers.length; i++) {
                    switch (headers[i]) {
                        case "STUDY":
                            entry.study = values[i];
                            break;
                        case "BIOENTITY":
                            entry.bioentity = values[i];
                            break;
                        case "PROPERTY_TYPE":
                            entry.propertyType = values[i];
                            break;
                        case "PROPERTY_VALUE":
                            entry.propertyValue = values[i];
                            break;
                        case "SEMANTIC_TAG":
                            entry.semanticTag = values[i];
                            break;
                        case "ANNOTATOR":
                            entry.annotator = values[i];
                            break;
                        case "ANNOTATION_DATE":
                            entry.annotationDate = values[i];
                            break;
                    }
                }

                String key = NormaliseString.normalise(entry.propertyValue);

                entries.computeIfAbsent(key, k -> new HashSet<>()).add(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading mapping table", e);
        }
    }

    public int getNumMappings() {
        return entries.keySet().size();
    }

    public Stream<MappingTableEntry> streamEntries() {
        return entries.values().stream()
            .flatMap(Set::stream);
    }

    public Stream<MappingTableEntry> streamEntriesForString(String stringToMap) {
        stringToMap = NormaliseString.normalise(stringToMap);
        Set<MappingTableEntry> result = entries.get(stringToMap);
        if (result == null) {
            return Stream.empty();
        }
        return result.stream();
    }
    
}
