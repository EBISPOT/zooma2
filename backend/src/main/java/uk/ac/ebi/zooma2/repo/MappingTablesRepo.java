package uk.ac.ebi.zooma2.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.Gson;

import uk.ac.ebi.zooma2.ZoomaConfig;
import uk.ac.ebi.zooma2.embedding.EmbeddingService;
import uk.ac.ebi.zooma2.embedding.VectorSearchIndex;

public class MappingTablesRepo {

    Gson gson = new Gson();

    public static String DATA_PATH = System.getenv().getOrDefault("ZOOMA2_DATA_PATH", "data");

    Map<String, MappingTable> tables = new LinkedHashMap<>();
    Set<String> allTypes = new LinkedHashSet<>();
    
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

        for (var dsEntry : ZoomaConfig.config.datasources.entrySet()) {

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

    /**
     * Get all distinct semantic tags (IRIs) from curated mapping tables.
     */
    public Set<String> getAllSemanticTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (MappingTable table : tables.values()) {
            table.streamEntries().forEach(entry -> {
                if (entry.semanticTag != null && !entry.semanticTag.isEmpty()) {
                    tags.add(entry.semanticTag);
                }
            });
        }
        return tags;
    }

    public Stream<MappingTableEntry> allMappingsForString(String stringToMap) {
        // Return both exact and embedding matches
        var exact = exactMappingsForString(stringToMap).toList();
        var embedding = embeddingMappingsForString(stringToMap).toList();
        
        List<MappingTableEntry> all = new ArrayList<>(exact);
        all.addAll(embedding);
        return all.stream();
    }

    /**
     * Get exact matches only from curated mapping tables.
     */
    public Stream<MappingTableEntry> exactMappingsForString(String stringToMap) {
        var exactMatches = tables.values().stream()
            .flatMap(t -> t.streamEntriesForString(stringToMap))
            .toList();

        System.err.println("Curated exact matches for '" + stringToMap + "': " + exactMatches.size());
        return exactMatches.stream();
    }

    /**
     * Get embedding-based matches only from local vector index.
     */
    public Stream<MappingTableEntry> embeddingMappingsForString(String stringToMap) {
        if (embeddingService == null || vectorIndex == null) {
            System.err.println("Local vector search disabled (embeddingService=" + (embeddingService != null) + 
                ", vectorIndex=" + (vectorIndex != null) + ")");
            return Stream.empty();
        }
        
        try {
            System.err.println("Running local vector search for '" + stringToMap + "'...");
            
            // Get embedding for search query
            float[] queryEmbedding = embeddingService.getEmbedding(stringToMap, "user_search");
            
            if (queryEmbedding == null) {
                System.err.println("Failed to get embedding for query");
                return Stream.empty();
            }
            
            // Search for similar vectors
            var searchResults = vectorIndex.search(queryEmbedding, 10, "mapping_table").stream()
                .filter(r -> r.score >= 0.8f)
                .toList();
            
            System.err.println("Local vector search found " + searchResults.size() + " similar property values (min similarity 0.8)");
            
            // Get mapping table entries for the similar values
            var similarMatches = searchResults.stream()
                .flatMap(result -> tables.values().stream()
                    .flatMap(t -> t.streamEntriesForString(result.text))
                    .peek(entry -> {
                        entry.fromEmbeddingSearch = true;
                        entry.originalSearchTerm = stringToMap;
                        entry.similarityScore = (double) result.score;
                    }))
                .toList();
            
            System.err.println("Curated semantic matches for '" + stringToMap + "': " + similarMatches.size());
            return similarMatches.stream();
            
        } catch (Exception e) {
            System.err.println("Error during local vector search: " + e.getMessage());
            e.printStackTrace();
            return Stream.empty();
        }
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

    public boolean isVectorIndexEnabled() {
        return vectorIndex != null && embeddingService != null;
    }

    public int getVectorIndexSize() {
        if (vectorIndex == null) return 0;
        try {
            return vectorIndex.getDocumentCount();
        } catch (Exception e) {
            return -1;
        }
    }

    public int getTotalMappingEntries() {
        return tables.values().stream().mapToInt(t -> t.getNumMappings()).sum();
    }
}
