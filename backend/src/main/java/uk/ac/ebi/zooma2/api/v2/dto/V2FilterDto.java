package uk.ac.ebi.zooma2.api.v2.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.javalin.http.BadRequestResponse;

// Filter parser for e.g. required:[atlas,gwas],preferred:[gwas],ontologies:[none],defining_only:[true]
public class V2FilterDto {
    public final List<String> required;
    public final List<String> preferred;
    public final List<String> ontologies;
    /** When true, restrict results to the filter ontologies' own namespaces (exclude imported terms). */
    public final boolean definingOnly;

    private V2FilterDto(List<String> required, List<String> preferred, List<String> ontologies, boolean definingOnly) {
        this.required = required;
        this.preferred = preferred;
        this.ontologies = ontologies;
        this.definingOnly = definingOnly;
    }

    private static final Pattern PART = Pattern.compile("\\s*([a-zA-Z_]+)\\s*:\\s*\\[(.*?)\\]\\s*");

    public static V2FilterDto parse(String raw) {
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
                    case "defining_only" -> definingOnly = uk.ac.ebi.zooma2.model.Filter.parseDefiningOnly(values);
                    default -> throw new BadRequestResponse("Unknown filter key: " + key);
                }
            }
        }

        return new V2FilterDto(required, preferred, ontologies, definingOnly);
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
            "ontologies", ontologies,
            "definingOnly", definingOnly
        );
    }

    /**
     * Convert this DTO to the internal Filter model.
     * V2's ontologies become targetOntologies with hard filter (includeOtherOntologies=false).
     */
    public uk.ac.ebi.zooma2.model.Filter toFilter() {
        return uk.ac.ebi.zooma2.model.Filter.fromLists(required, preferred, ontologies, ontologies.isEmpty(), definingOnly);
    }
}
