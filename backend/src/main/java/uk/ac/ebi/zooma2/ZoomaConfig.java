package uk.ac.ebi.zooma2;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class ZoomaConfig {

    public static final String CONFIG_PATH = System.getenv().getOrDefault("ZOOMA2_CONFIG_PATH", "config.json");

    public static final ZoomaConfig config = loadConfig();

    static ZoomaConfig loadConfig() {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(CONFIG_PATH)) {
            return gson.fromJson(reader, ZoomaConfig.class);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Config file not found: " + CONFIG_PATH, e);
        } catch (JsonSyntaxException e) {
            throw new RuntimeException("Invalid JSON in config file: " + CONFIG_PATH, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + CONFIG_PATH, e);
        }
    }

    public Map<String, Datasource> datasources;
    public Map<String, String> prefix_map;
    public EmbeddingConfig embedding;

    public static class Datasource {
        public String uri;
        public String import_url;
        public Map<String,String> column_map;
    }

    public static class EmbeddingConfig {
        public Integer batch_size; // Optional: embeddings per request (default: 50)
        public String database_path; // Path to SQLite database (legacy, now uses unified db)
    }
}
