
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

public class MappingTable {

    Map<String, Set<MappingTableEntry>> entries = new HashMap<>();

    public MappingTable(InputStream inputStream) {

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
                entries.computeIfAbsent(entry.semanticTag, k -> new HashSet<>()).add(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading mapping table", e);
        }
    }

    public int getNumMappings() {
        return entries.keySet().size();
    }
    
}
