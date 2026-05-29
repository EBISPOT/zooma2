package uk.ac.ebi.zooma2.rules;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Loads {@link RuleSet} JSON files from a directory and builds a {@link RuleEngine}.
 *
 * <p>Uses a Gson instance with custom deserializers for the typed {@link Condition}
 * and {@link Action} vocabularies (one discriminator key per element) and for
 * {@link RuleStage} (case-insensitive). Unknown condition/action/transform keys
 * fail loudly at load time so malformed rule files never silently degrade.
 */
public final class RuleSetLoader {
    private RuleSetLoader() {}

    /** CWD-relative rules directory; overridable via {@code ZOOMA2_RULES_PATH}. */
    public static final String RULES_PATH = System.getenv().getOrDefault("ZOOMA2_RULES_PATH", "rules");

    public static Gson gson() {
        return new GsonBuilder()
            .registerTypeAdapter(Condition.class, new ConditionDeserializer())
            .registerTypeAdapter(Action.class, new ActionDeserializer())
            .registerTypeAdapter(RuleStage.class, new RuleStageDeserializer())
            .create();
    }

    /**
     * Load from the configured rules path, falling back through a couple of common
     * CWD-relative locations so the engine works whether the app runs from the repo
     * root or the {@code backend/} module. Returns an empty engine if none exist.
     */
    public static RuleEngine loadDefault() {
        // Canonical location is the repo-root rules/ directory: "rules" when running
        // from the repo root, "../rules" when running from the backend/ module.
        for (String candidate : new String[]{ RULES_PATH, "../rules" }) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) return loadFrom(p);
        }
        System.err.println("No rules directory found (looked for "
            + RULES_PATH + ", ../rules); rule engine disabled.");
        return RuleEngine.empty();
    }

    public static RuleEngine loadFrom(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            System.err.println("Rules directory not found (" + dir + "); rule engine disabled.");
            return RuleEngine.empty();
        }
        Gson gson = gson();
        List<RuleSet> ruleSets = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jsonFiles = files
                .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted()
                .collect(Collectors.toList());
            for (Path p : jsonFiles) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    RuleSet rs = gson.fromJson(r, RuleSet.class);
                    if (rs != null) ruleSets.add(rs);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse rule file " + p + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list rules directory " + dir, e);
        }
        System.err.println("Loaded " + ruleSets.size() + " ruleset(s) from " + dir);
        return new RuleEngine(ruleSets);
    }

    /** Build an engine directly from one or more ruleset JSON strings (used by tests). */
    public static RuleEngine loadFromJson(String... ruleSetJson) {
        Gson gson = gson();
        List<RuleSet> rs = new ArrayList<>();
        for (String j : ruleSetJson) rs.add(gson.fromJson(j, RuleSet.class));
        return new RuleEngine(rs);
    }

    // ---- deserializers ----

    static final class RuleStageDeserializer implements JsonDeserializer<RuleStage> {
        public RuleStage deserialize(JsonElement json, Type t, JsonDeserializationContext ctx) {
            return RuleStage.valueOf(json.getAsString().trim().toUpperCase(Locale.ROOT));
        }
    }

    static final class ConditionDeserializer implements JsonDeserializer<Condition> {
        public Condition deserialize(JsonElement json, Type t, JsonDeserializationContext ctx) {
            if (!json.isJsonObject()) throw new JsonParseException("Condition must be an object: " + json);
            JsonObject obj = json.getAsJsonObject();
            if (obj.entrySet().size() != 1) {
                throw new JsonParseException("Condition must have exactly one key, got: " + obj.keySet());
            }
            Map.Entry<String, JsonElement> e = obj.entrySet().iterator().next();
            String key = e.getKey();
            JsonElement v = e.getValue();
            switch (key) {
                case "allOf": return new Conditions.AllOf(conditions(v, ctx));
                case "anyOf": return new Conditions.AnyOf(conditions(v, ctx));
                case "not": return new Conditions.Not(ctx.deserialize(v, Condition.class));
                case "queryMatches": return new Conditions.QueryMatches(v.getAsString());
                case "queryContainsAnyToken": return new Conditions.QueryContainsAnyToken(strings(v));
                case "queryContainsAllTokens": return new Conditions.QueryContainsAllTokens(strings(v));
                case "propertyTypeIn": return new Conditions.PropertyTypeIn(strings(v));
                case "candidateOntologyIn": return new Conditions.CandidateOntologyIn(strings(v));
                case "candidateIdIn": return new Conditions.CandidateIdIn(strings(v));
                case "candidateLabelMatches": return new Conditions.CandidateLabelMatches(v.getAsString());
                case "candidateLabelContainsAny": return new Conditions.CandidateLabelContainsAny(strings(v));
                case "candidateIsObsolete": return new Conditions.CandidateIsObsolete(v.getAsBoolean());
                case "candidateMethodIn": return new Conditions.CandidateMethodIn(strings(v));
                case "candidateScoreBelow": return new Conditions.CandidateScoreBelow(v.getAsDouble());
                case "candidateScoreAbove": return new Conditions.CandidateScoreAbove(v.getAsDouble());
                case "tokenOverlapBelow": return new Conditions.TokenOverlapBelow(v.getAsDouble());
                default: throw new JsonParseException("Unknown condition type: '" + key + "'");
            }
        }
        private List<Condition> conditions(JsonElement v, JsonDeserializationContext ctx) {
            List<Condition> out = new ArrayList<>();
            for (JsonElement el : v.getAsJsonArray()) out.add(ctx.deserialize(el, Condition.class));
            return out;
        }
    }

    static final class ActionDeserializer implements JsonDeserializer<Action> {
        public Action deserialize(JsonElement json, Type t, JsonDeserializationContext ctx) {
            if (!json.isJsonObject()) throw new JsonParseException("Action must be an object: " + json);
            JsonObject obj = json.getAsJsonObject();
            if (obj.entrySet().size() != 1) {
                throw new JsonParseException("Action must have exactly one key, got: " + obj.keySet());
            }
            Map.Entry<String, JsonElement> e = obj.entrySet().iterator().next();
            String key = e.getKey();
            JsonObject o = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
            switch (key) {
                case "reject": return new Actions.Reject(str(o, "reason", ""));
                case "boost": return new Actions.Boost(num(o, "amount"));
                case "penalize": return new Actions.Penalize(num(o, "amount"));
                case "normalize": return new Actions.Normalize(str(o, "pattern", null), str(o, "replacement", ""));
                case "applyTransform": {
                    String name = str(o, "name", null);
                    requireTransform(name);
                    return new Actions.ApplyTransform(name, stringMap(o.get("params")));
                }
                case "addVariant": {
                    String name = str(o, "transform", null);
                    requireTransform(name);
                    return new Actions.AddVariant(name);
                }
                case "addNormalizedVariant": {
                    List<Actions.AddNormalizedVariant.Sub> subs = new ArrayList<>();
                    JsonElement arr = o.get("subs");
                    if (arr != null && arr.isJsonArray()) {
                        for (JsonElement el : arr.getAsJsonArray()) {
                            JsonObject so = el.getAsJsonObject();
                            subs.add(new Actions.AddNormalizedVariant.Sub(
                                Pattern.compile(str(so, "pattern", "")), str(so, "replacement", "")));
                        }
                    }
                    boolean lc = o.has("lowercaseFirst") && o.get("lowercaseFirst").getAsBoolean();
                    return new Actions.AddNormalizedVariant(subs, lc);
                }
                case "setEffectiveType": return new Actions.SetEffectiveType(str(o, "value", null));
                case "parseEmbeddedId": return new Actions.ParseEmbeddedId();
                case "emitTerm": return new Actions.EmitTerm(str(o, "id", null), str(o, "label", null),
                    o.has("confidence") ? o.get("confidence").getAsDouble() : 0.95);
                case "preferOntologyOrder": return new Actions.PreferOntologyOrder(strings(o.get("list")));
                case "tieBreakByScope": return new Actions.TieBreakByScope();
                default: throw new JsonParseException("Unknown action type: '" + key + "'");
            }
        }
        private static void requireTransform(String name) {
            if (name == null || !NamedTransforms.has(name)) {
                throw new JsonParseException("Unknown transform: '" + name + "'");
            }
        }
    }

    // ---- json helpers (shared by the nested deserializers) ----

    private static List<String> strings(JsonElement v) {
        List<String> out = new ArrayList<>();
        if (v != null && v.isJsonArray()) {
            for (JsonElement el : v.getAsJsonArray()) out.add(el.getAsString());
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0.0;
    }

    private static Map<String, String> stringMap(JsonElement v) {
        if (v == null || !v.isJsonObject()) return Map.of();
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonNull()) m.put(e.getKey(), e.getValue().getAsString());
        }
        return m;
    }
}
