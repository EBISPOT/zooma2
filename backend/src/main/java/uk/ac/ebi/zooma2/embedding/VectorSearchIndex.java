package uk.ac.ebi.zooma2.embedding;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.*;

/**
 * Lucene-based vector search index for embeddings.
 * Uses KNN vector search for finding similar embeddings.
 */
public class VectorSearchIndex {

    private final Directory directory;
    private final IndexWriter writer;
    private final StandardAnalyzer analyzer;
    
    // Field names
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_METADATA = "metadata";
    
    private final int vectorDimension;

    public VectorSearchIndex(int vectorDimension) throws IOException {
        this.vectorDimension = vectorDimension;
        
        // Use in-memory directory for embedded database
        this.directory = new ByteBuffersDirectory();
        this.analyzer = new StandardAnalyzer();
        
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        
        // Use custom codec to support high-dimensional vectors (> 1024)
        config.setCodec(new HighDimensionCodec());
        
        this.writer = new IndexWriter(directory, config);
    }

    /**
     * Add a document with its embedding to the index.
     */
    public void addDocument(String text, float[] embedding, String source, Map<String, String> metadata) 
            throws IOException {
        
        if (embedding.length != vectorDimension) {
            throw new IllegalArgumentException(
                "Expected embedding dimension " + vectorDimension + " but got " + embedding.length);
        }

        Document doc = new Document();
        
        // Store the original text
        doc.add(new TextField(FIELD_TEXT, text, Field.Store.YES));
        doc.add(new StringField(FIELD_SOURCE, source, Field.Store.YES));
        
        // Add KNN vector field for similarity search (already float[])
        doc.add(new KnnFloatVectorField(FIELD_VECTOR, embedding, VectorSimilarityFunction.COSINE));
        
        // Store metadata as JSON string
        if (metadata != null && !metadata.isEmpty()) {
            StringBuilder metadataJson = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (!first) metadataJson.append(",");
                metadataJson.append("\"").append(entry.getKey()).append("\":\"")
                           .append(entry.getValue()).append("\"");
                first = false;
            }
            metadataJson.append("}");
            doc.add(new StoredField(FIELD_METADATA, metadataJson.toString()));
        }
        
        writer.addDocument(doc);
    }

    /**
     * Commit changes to the index.
     */
    public void commit() throws IOException {
        writer.commit();
    }

    /**
     * Search for similar vectors using KNN search.
     * @param queryVector The query embedding vector
     * @param topK Number of results to return
     * @param filter Optional filter on source
     * @return List of search results with text and similarity scores
     */
    public List<SearchResult> search(float[] queryVector, int topK, String filter) throws IOException {
        
        if (queryVector.length != vectorDimension) {
            throw new IllegalArgumentException(
                "Expected query dimension " + vectorDimension + " but got " + queryVector.length);
        }

        DirectoryReader reader = DirectoryReader.open(writer);
        IndexSearcher searcher = new IndexSearcher(reader);

        // Create KNN query (already float[])
        Query knnQuery = new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, topK);
        
        // Add filter if specified
        if (filter != null && !filter.isEmpty()) {
            Query filterQuery = new TermQuery(new Term(FIELD_SOURCE, filter));
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
            booleanQuery.add(knnQuery, BooleanClause.Occur.MUST);
            booleanQuery.add(filterQuery, BooleanClause.Occur.FILTER);
            knnQuery = booleanQuery.build();
        }

        TopDocs topDocs = searcher.search(knnQuery, topK);
        
        List<SearchResult> results = new ArrayList<>();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            
            SearchResult result = new SearchResult();
            result.text = doc.get(FIELD_TEXT);
            result.source = doc.get(FIELD_SOURCE);
            result.score = scoreDoc.score;
            result.metadata = doc.get(FIELD_METADATA);
            
            results.add(result);
        }
        
        reader.close();
        return results;
    }

    /**
     * Get the total number of documents in the index.
     */
    public int getDocumentCount() throws IOException {
        return writer.getDocStats().numDocs;
    }

    /**
     * Close the index.
     */
    public void close() throws IOException {
        if (writer != null && writer.isOpen()) {
            writer.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    public static class SearchResult {
        public String text;
        public String source;
        public float score;
        public String metadata;

        @Override
        public String toString() {
            return "SearchResult{text='" + text + "', source='" + source + 
                   "', score=" + score + ", metadata='" + metadata + "'}";
        }
    }
}
