package uk.ac.ebi.zooma2;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class ZoomaConfig {

    public static final String CONFIG_PATH = System.getenv().getOrDefault("ZOOMA2_CONFIG_PATH", "config.json");
    public static final String DEFAULT_OLS_URL = "https://www.ebi.ac.uk/ols4";

    public static volatile ZoomaConfig config = loadConfig();

    static ZoomaConfig loadConfig() {
        Gson gson = new Gson();
        try (Reader reader = openConfigReader()) {
            return gson.fromJson(reader, ZoomaConfig.class);
        } catch (JsonSyntaxException e) {
            throw new RuntimeException("Invalid JSON in config file: " + CONFIG_PATH, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + CONFIG_PATH, e);
        }
    }

    private static Reader openConfigReader() throws IOException {
        if (isRemoteConfigPath()) {
            HttpURLConnection connection = (HttpURLConnection) new URL(CONFIG_PATH).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            return new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
        }

        return Files.newBufferedReader(Paths.get(CONFIG_PATH), StandardCharsets.UTF_8);
    }

    private static boolean isRemoteConfigPath() {
        return CONFIG_PATH.startsWith("http://") || CONFIG_PATH.startsWith("https://");
    }

    /**
     * Start a daemon thread that watches config.json for changes and reloads it.
     */
    public static void startConfigWatcher() {
        if (isRemoteConfigPath()) {
            System.err.println("Config watcher disabled for remote config URL: " + CONFIG_PATH);
            return;
        }

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

    public Map<String, String> prefix_map;
    public OlsLexicalConfig ols_lexical;
    public OlsEmbeddingConfig ols_embedding;
    public OxoConfig oxo;
    public List<OntologyPreset> ontology_presets;

    public static String getOlsUrl() {
        return System.getenv().getOrDefault("ZOOMA2_OLS_URL", DEFAULT_OLS_URL);
    }

    public static class OlsLexicalConfig {
        public Integer max_results;
        public Integer timeout_ms;
        public Integer max_concurrent_requests;
    }

    public static class OlsEmbeddingConfig {
        public Double min_similarity;
        public Integer max_deep_results;
        public Integer max_shallow_results;
        public Integer timeout_ms;
        public Integer max_concurrent_embedding_requests;
        public Integer max_concurrent_similar_requests;
    }

    public static class OxoConfig {
        public Boolean enabled;
    }

    public static class OntologyPreset {
        public String name;
        public String description;
        public List<String> ontologies;
        public Boolean include_obo_ontologies;
    }
}
