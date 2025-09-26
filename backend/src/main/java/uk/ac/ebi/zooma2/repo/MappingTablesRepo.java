
package uk.ac.ebi.zooma2.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.Gson;

import uk.ac.ebi.zooma2.Zooma2Config;
import uk.ac.ebi.zooma2.util.NormaliseString;

public class MappingTablesRepo {

    Gson gson = new Gson();

    public static String DATA_PATH = System.getenv().getOrDefault("ZOOMA2_DATA_PATH", "data");

    Map<String, MappingTable> tables = new HashMap<>();
    Set<String> allTypes = new HashSet<>();

    public MappingTablesRepo() {
        loadTables(DATA_PATH);
    }

    public void loadTables(String path) {

        for (var dsEntry : Zooma2Config.config.datasources.entrySet()) {

            var databaseId = dsEntry.getKey();
            var dsConfig = dsEntry.getValue();

            MappingTable table = new MappingTable();

            for (String filename : dsConfig.files) {
                File file = new File(path, filename);
                if (!file.exists() || !file.canRead()) {
                    throw new IllegalArgumentException("Cannot read mapping table file: " + file.getAbsolutePath());
                }
                try (InputStream is = file.getName().endsWith(".gz")
                        ? new GZIPInputStream(new FileInputStream(file))
                        : new FileInputStream(file)) {
                    table.loadFromInputStream(is, databaseId, dsConfig.url);
                } catch (IOException e) {
                    throw new UncheckedIOException("Error reading mapping table file: " + file.getAbsolutePath(), e);
                }
            }

            System.err.println("Loaded " + table.entries.size() + " entries for datasource " + databaseId);

            tables.put(databaseId, table);
        }

        for(MappingTable table : tables.values()) {
            table.streamEntries().forEach(entry -> allTypes.add(entry.propertyType));
        }
    }

    public Set<String> getAllTypes() {
        return allTypes;
    }

    public Stream<MappingTableEntry> allMappingsForString(String stringToMap) {

        var res =  tables.values().stream()
            .flatMap(t -> t.streamEntriesForString(stringToMap));

        var list = res.toList();

        System.err.println("Mapped string '" + stringToMap + "' to " + gson.toJson(list));

        return list.stream();

        // return res;

    }

    
}
