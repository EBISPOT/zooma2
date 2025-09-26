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
    public final List<String> ontologies;

    private Filter(List<String> required, List<String> preferred, List<String> ontologies) {
        this.required = required;
        this.preferred = preferred;
        this.ontologies = ontologies;
    }

    private static final Pattern PART = Pattern.compile("\\s*([a-zA-Z]+)\\s*:\\s*\\[(.*?)\\]\\s*");

    public static Filter parse(String raw) {
        List<String> required = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        List<String> ontologies = new ArrayList<>();

        if (raw != null && !raw.isBlank()) {
            // Split on commas that are NOT inside brackets
            // This simple lexer walks the string to respect bracketed sections
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
                    default -> throw new BadRequestResponse("Unknown filter key: " + key);
                }
            }
        }

        return new Filter(required, preferred, ontologies);
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
            "ontologies", ontologies
        );
    }
}
