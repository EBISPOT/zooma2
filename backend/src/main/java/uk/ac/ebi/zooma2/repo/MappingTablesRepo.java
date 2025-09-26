
package uk.ac.ebi.zooma2.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class MappingTablesRepo {

    public static String DATA_PATH = System.getenv().getOrDefault("ZOOMA2_DATA_PATH", "data");

    Map<String, MappingTable> tables = new HashMap<>();

    public MappingTablesRepo() {

        for(String path : DATA_PATH.split(",")) {
            loadTables(path);
        }
        
    }

    public void loadTables(String path) {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    loadTables(file.getAbsolutePath());
                } else if (file.getName().endsWith(".gz")) {
                    try (InputStream fileStream = new FileInputStream(file);
                        InputStream gzipStream = new GZIPInputStream(fileStream)) {
                        MappingTable table = new MappingTable(gzipStream);
                        tables.put(file.getName(), table);
                        System.err.println("Loaded " + table.getNumMappings() + " mappings from file: " + file.getAbsolutePath());
                    } catch (IOException e) {
                        throw new UncheckedIOException(
                            "Error loading mapping table from file: " + file.getAbsolutePath(), e
                        );
                    }
                }
            }
        }
    }

    
}
