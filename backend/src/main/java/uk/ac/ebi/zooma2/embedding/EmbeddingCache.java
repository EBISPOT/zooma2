package uk.ac.ebi.zooma2.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite cache for embeddings stored as binary float32 arrays.
 */
public class EmbeddingCache {

    private final String jdbcUrl;

    /**
     * Create an embedding cache with SQLite.
     */
    public static EmbeddingCache createSqliteCache(String dbPath) {
        return new EmbeddingCache("jdbc:sqlite:" + dbPath);
    }

    private EmbeddingCache(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initializeDatabase();
    }

    private void initializeDatabase() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS embeddings (
                text TEXT NOT NULL,
                model TEXT NOT NULL,
                source_type TEXT NOT NULL,
                embedding BLOB NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (text, model, source_type)
            )
        """;

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            
            // Create index for faster lookups
            String createIndexSQL = "CREATE INDEX IF NOT EXISTS idx_embeddings_lookup " +
                                   "ON embeddings(text, model, source_type)";
            stmt.execute(createIndexSQL);
            
            System.err.println("Embedding cache database initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize embedding cache database", e);
        }
    }

    /**
     * Get cached embedding for a text string.
     * @return Embedding vector or null if not cached
     */
    public float[] getEmbedding(String text, String model, String sourceType) {
        String sql = "SELECT embedding FROM embeddings WHERE text = ? AND model = ? AND source_type = ?";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, text);
            stmt.setString(2, model);
            stmt.setString(3, sourceType);
            
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] binaryData = rs.getBytes("embedding");
                return deserializeEmbedding(binaryData);
            }
            return null;
        } catch (SQLException e) {
            System.err.println("Error retrieving embedding from cache: " + e.getMessage());
            return null;
        }
    }

    /**
     * Save embedding to cache.
     */
    public void saveEmbedding(String text, String model, String sourceType, float[] embedding) {
        String sql = "INSERT OR REPLACE INTO embeddings (text, model, source_type, embedding, created_at) " +
                     "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, text);
            stmt.setString(2, model);
            stmt.setString(3, sourceType);
            stmt.setBytes(4, serializeEmbedding(embedding));
            
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving embedding to cache: " + e.getMessage());
        }
    }

    /**
     * Get all cached embeddings for a specific source type.
     */
    public List<CachedEmbedding> getAllEmbeddingsForSource(String sourceType) {
        String sql = "SELECT text, model, source_type, embedding FROM embeddings WHERE source_type = ?";
        List<CachedEmbedding> results = new ArrayList<>();
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, sourceType);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                CachedEmbedding ce = new CachedEmbedding();
                ce.text = rs.getString("text");
                ce.model = rs.getString("model");
                ce.sourceType = rs.getString("source_type");
                ce.embedding = deserializeEmbedding(rs.getBytes("embedding"));
                results.add(ce);
            }
        } catch (SQLException e) {
            System.err.println("Error retrieving embeddings from cache: " + e.getMessage());
        }
        
        return results;
    }

    /**
     * Serialize embedding vector as binary float32 array.
     */
    private byte[] serializeEmbedding(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * Deserialize embedding vector from binary float32 array.
     */
    private float[] deserializeEmbedding(byte[] binaryData) {
        ByteBuffer buffer = ByteBuffer.wrap(binaryData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int numFloats = binaryData.length / 4;
        float[] embedding = new float[numFloats];
        for (int i = 0; i < numFloats; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }

    public void close() {
        // SQLite connections are managed per-operation with try-with-resources
        // No persistent connection to close
    }

    public static class CachedEmbedding {
        public String text;
        public String model;
        public String sourceType;
        public float[] embedding;
    }
}
