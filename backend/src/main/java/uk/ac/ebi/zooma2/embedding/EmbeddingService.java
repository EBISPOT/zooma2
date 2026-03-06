package uk.ac.ebi.zooma2.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Service for generating embeddings using EBI's internal embedding service.
 * Handles batching and caching of embeddings.
 * Discovers models from a models directory. PCA model files (e.g. model_pca512.json.gz)
 * are loaded and PCA projection is applied locally after calling the service with the base model.
 */
public class EmbeddingService {

    private static final String EMBEDDING_SERVICE_URL = "https://wwwdev.ebi.ac.uk/spot/embed";
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Pattern PCA_PATTERN = Pattern.compile("^(.+)_pca(\\d+)$");
    
    private final List<String> embeddingModels;
    private final EmbeddingCache cache;
    private final Gson gson;
    private final Map<String, Integer> modelDimensions = new HashMap<>();
    private final Map<String, PcaModel> pcaModels = new HashMap<>();
    private final int batchSize;

    private static class PcaModel {
        final String baseModelName;
        final int nComponents;
        final double[] mean;        // length = n_features
        final double[][] components; // shape = (n_features, n_components)

        PcaModel(String baseModelName, int nComponents, double[] mean, double[][] components) {
            this.baseModelName = baseModelName;
            this.nComponents = nComponents;
            this.mean = mean;
            this.components = components;
        }
    }

    /**
     * Create an embedding service that discovers models from a directory.
     * PCA model JSON files (*.json or *.json.gz) matching the pattern {base}_pca{N}
     * are loaded, and the PCA projection is applied locally.
     * @param cache Cache for storing embeddings
     * @param batchSize Number of embeddings per request (default: 50)
     * @param modelsDir Path to directory containing PCA model JSON files
     */
    public EmbeddingService(EmbeddingCache cache, int batchSize, String modelsDir) {
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.cache = cache;
        this.gson = new Gson();
        this.embeddingModels = new ArrayList<>();

        if (modelsDir != null && !modelsDir.isEmpty()) {
            loadModelsFromDirectory(modelsDir);
        }

        if (this.embeddingModels.isEmpty()) {
            System.err.println("Warning: No models found in models directory, vector search disabled");
        }

        System.err.println("EmbeddingService initialized with " + embeddingModels.size() + 
                         " models: " + String.join(", ", embeddingModels) + 
                         " (batch_size: " + this.batchSize + ")");
    }
    
    /**
     * Load models from a directory. Files matching *_pca*.json or *_pca*.json.gz
     * are loaded as PCA models. The model name is derived from the filename stem.
     */
    private void loadModelsFromDirectory(String modelsDir) {
        Path dir = Paths.get(modelsDir);
        if (!Files.isDirectory(dir)) {
            System.err.println("Models directory does not exist: " + modelsDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, entry -> {
                String n = entry.getFileName().toString();
                return n.matches(".*_pca\\d+\\.json(\\.gz)?");
            })) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String stem = filename.replaceFirst("\\.json(\\.gz)?$", "");
                Matcher m = PCA_PATTERN.matcher(stem);
                if (!m.matches()) continue;

                String baseModelName = m.group(1);
                int nComponents = Integer.parseInt(m.group(2));

                System.err.println("Loading PCA model: " + stem + " from " + file);

                try (Reader reader = openJsonReader(file)) {
                    JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                    double[] mean = toDoubleArray(json.getAsJsonArray("mean"));
                    double[][] components = toDoubleArray2D(json.getAsJsonArray("components"));

                    pcaModels.put(stem, new PcaModel(baseModelName, nComponents, mean, components));
                    embeddingModels.add(stem);

                    System.err.println("Loaded PCA model: " + stem +
                            " (base=" + baseModelName + ", components=" + nComponents +
                            ", features=" + mean.length + ")");
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading models from " + modelsDir + ": " + e.getMessage());
        }
    }

    private static Reader openJsonReader(Path file) throws IOException {
        if (file.toString().endsWith(".gz")) {
            return new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8);
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    private static double[] toDoubleArray(JsonArray arr) {
        double[] result = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = arr.get(i).getAsDouble();
        }
        return result;
    }

    private static double[][] toDoubleArray2D(JsonArray arr) {
        double[][] result = new double[arr.size()][];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = toDoubleArray(arr.get(i).getAsJsonArray());
        }
        return result;
    }

    /**
     * Apply PCA transform: (x - mean) @ components
     */
    private float[] applyPca(float[] embedding, PcaModel pca) {
        int nFeatures = pca.mean.length;
        int nComponents = pca.nComponents;
        float[] result = new float[nComponents];
        for (int j = 0; j < nComponents; j++) {
            double sum = 0.0;
            for (int i = 0; i < nFeatures; i++) {
                sum += ((double) embedding[i] - pca.mean[i]) * pca.components[i][j];
            }
            result[j] = (float) sum;
        }
        return result;
    }

    /**
     * Get embeddings for a list of strings using the primary model, using cache when possible.
     * Also embeds with all other models in background for comprehensive coverage.
     * @param texts List of strings to embed
     * @param sourceType Source type for cache (e.g., "mapping_table" or "user_search")
     * @return Map from text to embedding vector (using primary/first model)
     */
    public Map<String, float[]> getEmbeddings(List<String> texts, String sourceType) {
        String primaryModel = embeddingModels.get(0);
        Map<String, float[]> result = new HashMap<>();
        
        // For each model, check cache and embed if needed
        for (String model : embeddingModels) {
            List<String> textsToEmbed = new ArrayList<>();
            
            // Check cache for this model
            for (String text : texts) {
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                
                float[] cached = cache.getEmbedding(text, model, sourceType);
                if (cached != null && model.equals(primaryModel)) {
                    result.put(text, cached);
                } else if (cached == null) {
                    textsToEmbed.add(text);
                }
            }
            
            // Embed remaining texts for this model in batches
            if (!textsToEmbed.isEmpty()) {
                int totalBatches = (int) Math.ceil((double) textsToEmbed.size() / batchSize);
                System.err.println("[" + model + "] Embedding " + textsToEmbed.size() + 
                                 " new texts in " + totalBatches + " batches (from " + sourceType + ")");
                
                int batchNum = 0;
                for (int i = 0; i < textsToEmbed.size(); i += batchSize) {
                    batchNum++;
                    int end = Math.min(i + batchSize, textsToEmbed.size());
                    List<String> batch = textsToEmbed.subList(i, end);
                    
                    System.err.println("[" + model + "] Processing batch " + batchNum + "/" + totalBatches + 
                                     " (" + batch.size() + " texts)...");
                    
                    Map<String, float[]> batchResults = embedBatch(batch, model);
                    
                    // Add to result only if this is the primary model
                    if (model.equals(primaryModel)) {
                        result.putAll(batchResults);
                    }
                    
                    // Cache the results for this model
                    for (Map.Entry<String, float[]> entry : batchResults.entrySet()) {
                        cache.saveEmbedding(entry.getKey(), model, sourceType, entry.getValue());
                    }
                    
                    System.err.println("[" + model + "] Batch " + batchNum + "/" + totalBatches + " complete");
                }
            }
        }

        return result;
    }

    /**
     * Get embedding for a single string using primary model.
     * Also embeds with all other models for comprehensive coverage.
     * @param text Text to embed
     * @param sourceType Source type for cache
     * @return Embedding vector (from primary model) or null if text is empty
     */
    public float[] getEmbedding(String text, String sourceType) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        String primaryModel = embeddingModels.get(0);
        float[] primaryEmbedding = null;
        
        // Embed with all models
        for (String model : embeddingModels) {
            float[] cached = cache.getEmbedding(text, model, sourceType);
            if (cached != null) {
                if (model.equals(primaryModel)) {
                    primaryEmbedding = cached;
                }
                continue;
            }
            
            Map<String, float[]> result = embedBatch(List.of(text), model);
            float[] embedding = result.get(text);
            if (embedding != null) {
                cache.saveEmbedding(text, model, sourceType, embedding);
                if (model.equals(primaryModel)) {
                    primaryEmbedding = embedding;
                }
            }
        }
        
        return primaryEmbedding;
    }

    /**
     * Embed a batch of texts using EBI embedding service with specified model.
     * If the model is a PCA model, calls the service with the base model name
     * and applies PCA projection locally.
     */
    private Map<String, float[]> embedBatch(List<String> texts, String model) {
        PcaModel pca = pcaModels.get(model);
        String serviceModel = (pca != null) ? pca.baseModelName : model;

        try {
            // Prepare request
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", serviceModel);
            requestBody.put("text", texts);
            
            String jsonBody = gson.toJson(requestBody);
            
            // Log request for debugging
            if (texts.size() <= 3) {
                System.err.println("[" + model + "] Request: " + jsonBody);
            }
            
            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(30000)
                    .setConnectionRequestTimeout(30000)
                    .setSocketTimeout(30000).build();

            HttpPost httpPost = new HttpPost(EMBEDDING_SERVICE_URL);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(jsonBody, "UTF-8"));

            try (CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(config).build()) {
                HttpResponse httpResponse = client.execute(httpPost);
                int statusCode = httpResponse.getStatusLine().getStatusCode();
                byte[] responseData = EntityUtils.toByteArray(httpResponse.getEntity());

                if (statusCode != 200) {
                    System.err.println("[" + model + "] HTTP error " + statusCode);
                    System.err.println("[" + model + "] Error response: " + new String(responseData));
                    System.err.println("[" + model + "] Request was: " + jsonBody.substring(0, Math.min(500, jsonBody.length())));
                    return new HashMap<>();
                }

                if (responseData == null || responseData.length == 0) {
                    System.err.println("[" + model + "] Empty response from embedding service");
                    return new HashMap<>();
                }

                // Get embedding dimension from header FIRST
                int dimension = modelDimensions.getOrDefault(model, -1);
                var dimHeaderObj = httpResponse.getFirstHeader("x-embedding-dim");
                String dimHeader = dimHeaderObj != null ? dimHeaderObj.getValue() : null;
                if (dimHeader != null) {
                    dimension = Integer.parseInt(dimHeader);
                    if (!modelDimensions.containsKey(model)) {
                        modelDimensions.put(model, dimension);
                        System.err.println("[" + model + "] Embedding dimension: " + dimension);
                    }
                }

                if (dimension == -1) {
                    System.err.println("[" + model + "] Error: No embedding dimension in response header");
                    return new HashMap<>();
                }

                // Read binary response as float32 array
                List<float[]> embeddings = parseBinaryEmbeddings(responseData, texts.size(), dimension);

                // Apply PCA if this is a PCA model
                if (pca != null) {
                    for (int i = 0; i < embeddings.size(); i++) {
                        embeddings.set(i, applyPca(embeddings.get(i), pca));
                    }
                }

                // Map back to texts
                Map<String, float[]> result = new HashMap<>();
                for (int i = 0; i < texts.size() && i < embeddings.size(); i++) {
                    result.put(texts.get(i), embeddings.get(i));
                }

                return result;
            }
        } catch (IOException e) {
            System.err.println("[" + model + "] Error embedding batch: " + e.getMessage());
            e.printStackTrace();
            return new HashMap<>();
        }
    }
    
    /**
     * Parse binary float32 embeddings from response.
     */
    private List<float[]> parseBinaryEmbeddings(byte[] data, int numTexts, int dimension) {
        List<float[]> embeddings = new ArrayList<>();
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // float32 is typically little-endian
        
        int expectedFloats = numTexts * dimension;
        int availableFloats = data.length / 4; // 4 bytes per float32
        
        if (availableFloats < expectedFloats) {
            System.err.println("Warning: received fewer floats than expected (" + 
                             availableFloats + " vs " + expectedFloats + ")");
            System.err.println("This likely means the embedding service returned an error or incomplete data.");
            System.err.println("Received " + data.length + " bytes, expected " + (expectedFloats * 4) + " bytes");
            // Return empty result rather than crashing
            return embeddings;
        }
        
        for (int i = 0; i < numTexts; i++) {
            float[] embedding = new float[dimension];
            for (int j = 0; j < dimension; j++) {
                if (!buffer.hasRemaining()) {
                    System.err.println("Warning: buffer exhausted at text " + i + ", dimension " + j);
                    break;
                }
                embedding[j] = buffer.getFloat();
            }
            embeddings.add(embedding);
        }
        
        return embeddings;
    }

    public List<String> getEmbeddingModels() {
        return new ArrayList<>(embeddingModels);
    }
    
    public String getPrimaryModel() {
        return embeddingModels.isEmpty() ? null : embeddingModels.get(0);
    }
    
    public int getEmbeddingDimension() {
        String primaryModel = getPrimaryModel();
        if (primaryModel == null) return -1;
        // PCA models have a known output dimension
        PcaModel pca = pcaModels.get(primaryModel);
        if (pca != null) return pca.nComponents;
        return modelDimensions.getOrDefault(primaryModel, -1);
    }
    
    public EmbeddingCache getCache() {
        return cache;
    }
}
