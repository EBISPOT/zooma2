
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

    public void loadFromInputStream(InputStream inputStream, String databaseId, String databaseUrl, Map<String,String> columnMap) {

        Map<String,String> columnNamesToUse = new HashMap<>();

        for(String key : new String[]{"STUDY","BIOENTITY","PROPERTY_TYPE","PROPERTY_VALUE","SEMANTIC_TAG","ANNOTATOR","ANNOTATION_DATE"}) {
            columnNamesToUse.put(key, key);
        }

        if(columnMap != null) {
            for(String key : columnMap.keySet()) {
                columnNamesToUse.put(columnMap.get(key), key);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String headerLine = reader.readLine();
            // Skip comment lines (e.g. in test data extracts)
            while (headerLine != null && headerLine.startsWith("#")) {
                headerLine = reader.readLine();
            }
            if (headerLine == null) {
                return;
            }

            // dumb heuristic to guess if csv or tsv
            long nCommas = headerLine.chars().filter(c -> c == ',').count();
            long nTabs = headerLine.chars().filter(c -> c == '\t').count();
            String delimiter = "\t";
            if (nCommas > nTabs) {
                // System.err.println("Detected CSV format for " + databaseId);
                delimiter = ",";
            } else {
                // System.err.println("Detected TSV format for " + databaseId);
            }


            String[] headers = headerLine.split(delimiter);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] values = line.split(delimiter);

                MappingTableEntry entry = new MappingTableEntry();
                entry.databaseId = databaseId;
                entry.databaseUrl = databaseUrl;

                for (int i = 0; i < headers.length; i++) {

                    if(headers[i].equals(columnNamesToUse.get("STUDY"))) {
                        entry.study = values[i];
                    } else if(headers[i].equals(columnNamesToUse.get("BIOENTITY"))) {
                        entry.bioentity = values[i];
                    } else if(headers[i].equals(columnNamesToUse.get("PROPERTY_TYPE"))) {
                        entry.propertyType = values[i];
                    } else if(headers[i].equals(columnNamesToUse.get("PROPERTY_VALUE"))) {
                        entry.propertyValue = values[i];
                    } else if(headers[i].equals(columnNamesToUse.get("SEMANTIC_TAG"))) {
                        entry.semanticTag = values[i];
                    } else if(headers[i].equals(columnNamesToUse.get("ANNOTATOR"))) {
                        entry.annotator = values[i];
                    } else if(headers[i].equals(columnNamesToUse.get("ANNOTATION_DATE"))) {
                        entry.annotationDate = values[i];
                    } else {
                        // System.err.println("Skipping unknown column in mapping table: " + headers[i]);
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
