package uk.ac.ebi.zooma2;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class ZoomaConfig {

    public static final String CONFIG_PATH = System.getenv().getOrDefault("ZOOMA2_CONFIG_PATH", "config.json");

    public static volatile ZoomaConfig config = loadConfig();

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

    /**
     * Start a daemon thread that watches config.json for changes and reloads it.
     */
    public static void startConfigWatcher() {
        Path configPath = Paths.get(CONFIG_PATH).toAbsolutePath();
        Path dir = configPath.getParent();
        Path fileName = configPath.getFileName();

        Thread watcher = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);
                while (true) {
                    WatchKey key = ws.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.context() instanceof Path changed && changed.equals(fileName)) {
                            // Small delay — editors often write in multiple steps
                            Thread.sleep(500);
                            try {
                                config = loadConfig();
                                System.err.println("Config reloaded from " + CONFIG_PATH);
                            } catch (Exception e) {
                                System.err.println("Failed to reload config: " + e.getMessage());
                            }
                        }
                    }
                    key.reset();
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Config watcher stopped: " + e.getMessage());
            }
        }, "config-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    public Map<String, Datasource> datasources;
    public Map<String, String> prefix_map;
    public EmbeddingConfig local_embedding;
    public OlsEmbeddingConfig ols_embedding;
    public List<OntologyPreset> ontology_presets;

    public static class Datasource {
        public String uri;
        public String import_url;
        public Map<String,String> column_map;
    }

    public static class EmbeddingConfig {
        public Integer batch_size; // Optional: embeddings per request (default: 50)
        public String database_path; // Path to SQLite database (legacy, now uses unified db)
    }

    public static class OlsEmbeddingConfig {
        public Double min_similarity;
        public Integer max_results;
        public Integer timeout_ms;
    }

    public static class OntologyPreset {
        public String name;
        public String description;
        public List<String> ontologies;
        public Boolean include_obo_ontologies;
    }
}
