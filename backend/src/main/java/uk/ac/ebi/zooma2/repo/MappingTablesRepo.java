package uk.ac.ebi.zooma2.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.Gson;

import uk.ac.ebi.zooma2.Zooma2Config;
import uk.ac.ebi.zooma2.embedding.EmbeddingService;
import uk.ac.ebi.zooma2.embedding.VectorSearchIndex;

public class MappingTablesRepo {

    Gson gson = new Gson();

    public static String DATA_PATH = System.getenv().getOrDefault("ZOOMA2_DATA_PATH", "data");

    Map<String, MappingTable> tables = new HashMap<>();
    Set<String> allTypes = new HashSet<>();
    
    // Vector search components
    private VectorSearchIndex vectorIndex;
    private EmbeddingService embeddingService;

    public MappingTablesRepo() {
        loadTables(DATA_PATH);
    }

    public MappingTablesRepo(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        loadTables(DATA_PATH);
        
        // Build vector index if embedding service is available
        if (embeddingService != null) {
            buildVectorIndex();
        }
    }

    public void loadTables(String path) {

        for (var dsEntry : Zooma2Config.config.datasources.entrySet()) {

            var databaseId = dsEntry.getKey();
            var dsConfig = dsEntry.getValue();

            MappingTable table = new MappingTable();

            String filename = dsConfig.import_url.split("/")[dsConfig.import_url.split("/").length - 1] + ".gz";

            System.err.println("Loading mapping table for datasource " + databaseId + " from file " + filename);

            File file = new File(path, filename);
            if (!file.exists() || !file.canRead()) {
                throw new IllegalArgumentException("Cannot read mapping table file: " + file.getAbsolutePath());
            }
            try{
                InputStream is = new GZIPInputStream(new FileInputStream(file));
                table.loadFromInputStream(is, databaseId, dsConfig.uri, dsConfig.column_map);
            } catch (IOException e) {
                throw new UncheckedIOException("Error reading mapping table file: " + file.getAbsolutePath(), e);
            }

            System.err.println("Loaded " + table.entries.size() + " entries for datasource " + databaseId);

            tables.put(databaseId, table);
        }

        for(MappingTable table : tables.values()) {
            table.streamEntries().forEach(entry -> allTypes.add(entry.propertyType));
        }
    }

    /**
     * Build the vector index by embedding all unique property values from mapping tables.
     */
    private void buildVectorIndex() {
        System.err.println("Building vector index for mapping tables...");
        
        // Collect all unique property values
        Set<String> uniqueValues = new HashSet<>();
        for (MappingTable table : tables.values()) {
            table.streamEntries().forEach(entry -> {
                if (entry.propertyValue != null && !entry.propertyValue.isEmpty()) {
                    uniqueValues.add(entry.propertyValue);
                }
            });
        }

        System.err.println("Found " + uniqueValues.size() + " unique property values");

        // Embed all unique values in batches
        List<String> valuesList = new ArrayList<>(uniqueValues);
        Map<String, float[]> embeddings = embeddingService.getEmbeddings(valuesList, "mapping_table");

        if (embeddings.isEmpty()) {
            System.err.println("No embeddings generated, vector search will be disabled");
            return;
        }

        // Initialize vector index with correct dimension
        int dimension = embeddingService.getEmbeddingDimension();
        if (dimension == -1) {
            // Get dimension from first embedding
            dimension = embeddings.values().iterator().next().length;
        }
        
        try {
            vectorIndex = new VectorSearchIndex(dimension);
        } catch (IOException e) {
            System.err.println("Failed to initialize vector search index: " + e.getMessage());
            return;
        }

        // Add to vector index
        int indexed = 0;
        for (Map.Entry<String, float[]> entry : embeddings.entrySet()) {
            try {
                Map<String, String> metadata = new HashMap<>();
                metadata.put("propertyValue", entry.getKey());
                
                vectorIndex.addDocument(
                    entry.getKey(),
                    entry.getValue(),
                    "mapping_table",
                    metadata
                );
                indexed++;
            } catch (IOException e) {
                System.err.println("Error adding document to vector index: " + e.getMessage());
            }
        }

        try {
            vectorIndex.commit();
            System.err.println("Vector index built with " + indexed + " documents");
        } catch (IOException e) {
            System.err.println("Error committing vector index: " + e.getMessage());
        }
    }

    public Set<String> getAllTypes() {
        return allTypes;
    }

    public Stream<MappingTableEntry> allMappingsForString(String stringToMap) {

        // Try exact match first (existing behavior)
        var exactMatches = tables.values().stream()
            .flatMap(t -> t.streamEntriesForString(stringToMap))
            .toList();

        if (!exactMatches.isEmpty()) {
            System.err.println("Mapped string '" + stringToMap + "' to " + exactMatches.size() + " exact matches");
            return exactMatches.stream();
        }

        // Fallback to vector search if no exact matches
        if (embeddingService != null && vectorIndex != null) {
            System.err.println("No exact matches for '" + stringToMap + "', trying vector search...");
            
            try {
                // Get embedding for search query
                float[] queryEmbedding = embeddingService.getEmbedding(stringToMap, "user_search");
                
                if (queryEmbedding != null) {
                    // Search for similar vectors
                    var searchResults = vectorIndex.search(queryEmbedding, 10, "mapping_table");
                    
                    System.err.println("Vector search found " + searchResults.size() + " similar values");
                    
                    // Get mapping table entries for the similar values
                    var similarMatches = searchResults.stream()
                        .flatMap(result -> tables.values().stream()
                            .flatMap(t -> t.streamEntriesForString(result.text)))
                        .toList();
                    
                    System.err.println("Mapped string '" + stringToMap + "' to " + similarMatches.size() + 
                                     " matches via vector search");
                    return similarMatches.stream();
                }
            } catch (Exception e) {
                System.err.println("Error during vector search: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.err.println("No mappings found for string '" + stringToMap + "'");
        return Stream.empty();
    }

    public void close() {
        if (vectorIndex != null) {
            try {
                vectorIndex.close();
            } catch (IOException e) {
                System.err.println("Error closing vector index: " + e.getMessage());
            }
        }
    }
}
