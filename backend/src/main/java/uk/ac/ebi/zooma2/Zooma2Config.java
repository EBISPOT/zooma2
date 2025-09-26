package uk.ac.ebi.zooma2;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class Zooma2Config {

    public static final String CONFIG_PATH = System.getenv().getOrDefault("ZOOMA2_CONFIG_PATH", "config.json");

    public static final Zooma2Config config = loadConfig();

    static Zooma2Config loadConfig() {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(CONFIG_PATH)) {
            return gson.fromJson(reader, Zooma2Config.class);
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

    public static class Datasource {
        public String url;
        public List<String> files;
    }
}
