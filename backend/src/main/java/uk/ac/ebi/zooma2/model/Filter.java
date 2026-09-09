package uk.ac.ebi.zooma2.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.javalin.http.BadRequestResponse;

// Filter parser for e.g. required:[atlas,gwas],preferred:[gwas],ontologies:[none]
public class Filter {
    public final List<String> required;
    public final List<String> preferred;
    public final List<String> targetOntologies;
    public final boolean includeOtherOntologies;
    /** When true, restrict results to the target ontologies' own namespaces (exclude imported terms). */
    public final boolean definingOnly;

    private Filter(List<String> required, List<String> preferred, List<String> targetOntologies,
                   boolean includeOtherOntologies, boolean definingOnly) {
        this.required = required;
        this.preferred = preferred;
        this.targetOntologies = targetOntologies;
        this.includeOtherOntologies = includeOtherOntologies;
        this.definingOnly = definingOnly;
    }

    /**
     * Factory method to create a Filter from lists.
     * Used by API layers to convert from DTOs to internal Filter.
     */
    public static Filter fromLists(List<String> required, List<String> preferred, List<String> targetOntologies, boolean includeOtherOntologies) {
        return fromLists(required, preferred, targetOntologies, includeOtherOntologies, false);
    }

    public static Filter fromLists(List<String> required, List<String> preferred, List<String> targetOntologies,
                                   boolean includeOtherOntologies, boolean definingOnly) {
        return new Filter(
            required != null ? required : new ArrayList<>(),
            preferred != null ? preferred : new ArrayList<>(),
            targetOntologies != null ? targetOntologies : new ArrayList<>(),
            includeOtherOntologies,
            definingOnly
        );
    }

    private static final Pattern PART = Pattern.compile("\\s*([a-zA-Z_]+)\\s*:\\s*\\[(.*?)\\]\\s*");

    public static Filter parse(String raw) {
        List<String> required = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        List<String> ontologies = new ArrayList<>();
        boolean definingOnly = false;

        if (raw != null && !raw.isBlank()) {
            List<String> parts = smartSplit(raw);

            for (String p : parts) {
                Matcher m = PART.matcher(p);
                if (!m.matches()) {
                    throw new BadRequestResponse("Malformed filter part: '" + p.trim() + "'");
                }
                String key = m.group(1).toLowerCase(Locale.ROOT);
                String items = m.group(2).trim();
                List<String> values = items.isEmpty()
                    ? List.of()
                    : Arrays.stream(items.split("\\s*,\\s*"))
                            .filter(s -> !s.isBlank())
                            .toList();

                switch (key) {
                    case "required"   -> required.addAll(values);
                    case "preferred"  -> preferred.addAll(values);
                    case "ontologies" -> ontologies.addAll(values);
                    case "defining_only" -> definingOnly = parseDefiningOnly(values);
                    default -> throw new BadRequestResponse("Unknown filter key: " + key);
                }
            }
        }

        // v2 parse: ontologies become targetOntologies with hard filter (includeOtherOntologies=false)
        return new Filter(required, preferred, ontologies, ontologies.isEmpty(), definingOnly);
    }

    /** Parses the single true/false value of a {@code defining_only:[...]} filter part. */
    public static boolean parseDefiningOnly(List<String> values) {
        if (values.size() != 1
                || !(values.get(0).equalsIgnoreCase("true") || values.get(0).equalsIgnoreCase("false"))) {
            throw new BadRequestResponse("defining_only takes a single true/false value, e.g. defining_only:[true]");
        }
        return values.get(0).equalsIgnoreCase("true");
    }

    private static List<String> smartSplit(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            if (c == ']') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "required", required,
            "preferred", preferred,
            "targetOntologies", targetOntologies,
            "includeOtherOntologies", includeOtherOntologies,
            "definingOnly", definingOnly
        );
    }
}
