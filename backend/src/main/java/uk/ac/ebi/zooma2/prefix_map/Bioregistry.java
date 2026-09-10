package uk.ac.ebi.zooma2.prefix_map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import uk.ac.ebi.zooma2.util.CachedHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The Bioregistry (https://bioregistry.io) is an open source, community curated registry
 * of prefixes for biomedical ontologies/vocabularies and their associated metadata. It
 * can be used to look generate links for prefixes found in xrefs and other components of
 * ontologies.
 *
 * <p>Source code and data is available under CC0/MIT licenses at https://github.com/biopragmatics/bioregistry
 *
 * <p>Construction never touches the network: a vendored snapshot on the classpath
 * ({@value #SNAPSHOT_RESOURCE}, refreshed with {@code backend/scripts/update-bioregistry-snapshot.py})
 * is loaded first, so a cold start does not depend on GitHub. The live registry is
 * then fetched on a background thread and, if that succeeds, swapped in atomically;
 * readers always see one complete, immutable index.
 */
public class Bioregistry {

    public static final String DEFAULT_URL = "https://raw.githubusercontent.com/biopragmatics/bioregistry/main/exports/registry/registry.json";
    static final String SNAPSHOT_RESOURCE = "/bioregistry/registry.json";

    public final String registryUrl;

    private final AtomicReference<Index> index;

    /** Lookup structures built from one registry document; never mutated after construction. */
    private static final class Index {
        final Map<String, JsonObject> prefixToDatabase = new HashMap<>();
        final TreeMap<String, String> iriPrefixToDatabase = new TreeMap<>((s1, s2) -> {
            if (s1.length() > s2.length()) return -1;
            if (s1.length() < s2.length()) return 1;
            return s1.compareTo(s2);
        });
        final ConcurrentHashMap<String, Pattern> patterns = new ConcurrentHashMap<>();
        final int size;

        Index(JsonObject registry) {
            this.size = registry.size();
            for (var entry : registry.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject db = entry.getValue().getAsJsonObject();

                // The key is the canonical Bioregistry prefix, always lowercase
                prefixToDatabase.put(norm(entry.getKey()), db);

                // The preferred prefix can have various capitalization, usually the same as canonical
                JsonElement preferred = db.get("preferred_prefix");
                if (preferred != null && preferred.isJsonPrimitive()) {
                    prefixToDatabase.put(norm(preferred.getAsString()), db);
                }

                JsonElement synonyms = db.get("synonyms");
                if (synonyms != null && synonyms.isJsonArray()) {
                    for (JsonElement synonym : synonyms.getAsJsonArray()) {
                        prefixToDatabase.put(norm(synonym.getAsString()), db);
                    }
                }

                JsonElement uriFormat = db.get("uri_format");
                if (uriFormat != null && uriFormat.isJsonPrimitive()) {
                    String format = uriFormat.getAsString();
                    // TODO: based on charlie's PR but maybe we should regex match the format?
                    if (format.endsWith("$1")) {
                        String uriPrefix = format.substring(0, format.length() - 2);
                        iriPrefixToDatabase.put(uriPrefix, entry.getKey());
                    }
                }
            }
        }
    }

    /** Snapshot now, live registry when the background refresh completes. */
    public Bioregistry() {
        this(DEFAULT_URL, true);
    }

    /** Snapshot now, refreshed in the background from {@code jsonUrl}. */
    public Bioregistry(String jsonUrl) {
        this(jsonUrl, true);
    }

    /** The vendored snapshot only, no network at all; for tests and offline use. */
    public static Bioregistry fromSnapshot() {
        return new Bioregistry(null, false);
    }

    private Bioregistry(String jsonUrl, boolean refresh) {
        this.registryUrl = jsonUrl;
        this.index = new AtomicReference<>(new Index(loadSnapshot()));
        if (refresh && jsonUrl != null) {
            startRefresh(jsonUrl);
        }
    }

    private static JsonObject loadSnapshot() {
        try (InputStream in = Bioregistry.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bioregistry snapshot missing from classpath: " + SNAPSHOT_RESOURCE);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Bioregistry snapshot " + SNAPSHOT_RESOURCE, e);
        }
    }

    private void startRefresh(String url) {
        Thread refresh = new Thread(() -> {
            try {
                JsonObject fresh = urlToJson(url).getAsJsonObject();
                Index built = new Index(fresh);
                index.set(built);
                System.err.println("Bioregistry refreshed from " + url + " (" + built.size + " entries)");
            } catch (Exception e) {
                System.err.println("Bioregistry refresh from " + url + " failed, keeping the vendored snapshot: "
                    + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
        }, "bioregistry-refresh");
        refresh.setDaemon(true);
        refresh.start();
    }

    public String getRegistryUrl() {
        return registryUrl;
    }

    /** Number of registry entries in the index currently in use. */
    public int size() {
        return index.get().size;
    }

    public String getUrlForId(String databaseId, String id) {
        Index current = index.get();
        JsonObject db = current.prefixToDatabase.get(norm(databaseId));
        if (db == null) return null;
        if (id == null) return null;

        JsonElement patternObj = db.get("pattern");
        if (patternObj == null) return null;

        Pattern pattern = current.patterns.computeIfAbsent(patternObj.getAsString(), Pattern::compile);
        if (!pattern.matcher(id).matches()) {
            return null;
        }

        JsonElement uriFormat = db.get("uri_format");
        if (uriFormat == null) {
            return null;
        }
        return uriFormat.getAsString().replace("$1", id);
    }

    public String getCurieForUrl(String url) {
        for (var entry : index.get().iriPrefixToDatabase.entrySet()) {
            String key = entry.getKey();
            if (url.startsWith(key)) {
                String localUniqueIdentifier = url.substring(key.length());
                return entry.getValue() + ":" + localUniqueIdentifier;
            }
        }
        return null;
    }

    private static String norm(String s) {
        // see https://github.com/biopragmatics/bioregistry/blob/a7424ef4a0d22eaca61d3a86c6175e2059e9c855/src/bioregistry/utils.py#L128-L133
        s = s.toLowerCase(Locale.ROOT);
        s = s.replace(".", "");
        s = s.replace("-", "");
        s = s.replace("_", "");
        s = s.replace("/", "");
        return s;
    }

    private JsonElement urlToJson(String url) throws IOException {
        return CachedHttpClient.getJsonWithSystemProperties(url, 5000);
    }
}
