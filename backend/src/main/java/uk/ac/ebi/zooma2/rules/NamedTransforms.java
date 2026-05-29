package uk.ac.ebi.zooma2.rules;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registry of named text transforms — the code side of the declarative boundary.
 *
 * <p>Rule files invoke these by name (via the {@code applyTransform} / {@code addVariant}
 * actions); the transform implementations live here so configuration never executes
 * arbitrary code. Adding a new transform is a deliberate, reviewable code change.
 */
public final class NamedTransforms {
    private NamedTransforms() {}

    @FunctionalInterface
    public interface Transform { String apply(String input, Map<String, String> params); }

    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^()]*\\)");
    private static final Pattern WS = Pattern.compile("\\s+");

    // Only domain-agnostic transforms live here. Domain-specific normalisation
    // (e.g. self-report / cohort phrasing) belongs in the rules as a `normalize`
    // action carrying its own regex, not as a baked-in transform.
    private static final Map<String, Transform> REGISTRY = Map.of(
        "stripParentheticals", (in, p) -> in == null ? null
            : WS.matcher(PARENTHETICAL.matcher(in).replaceAll(" ")).replaceAll(" ").trim(),
        "collapseDuplicateTokens", (in, p) -> collapseDuplicates(in),
        "lowercaseTrim", (in, p) -> in == null ? null : in.trim().toLowerCase(Locale.ROOT)
    );

    public static boolean has(String name) { return REGISTRY.containsKey(name); }

    public static String apply(String name, String input, Map<String, String> params) {
        Transform t = REGISTRY.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown transform: '" + name + "'");
        return t.apply(input, params);
    }

    private static String collapseDuplicates(String in) {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String t : in.trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            if (prev == null || !t.equalsIgnoreCase(prev)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
            prev = t;
        }
        return sb.toString();
    }
}
