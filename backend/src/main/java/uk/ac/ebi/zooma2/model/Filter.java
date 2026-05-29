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
    /**
     * When {@code true}, deduplication collapses to a single best result per
     * target ontology. Opt-in: callers that want a full candidate set (e.g.
     * for downstream re-ranking) should leave this {@code false}.
     */
    public final boolean limitPerOntology;
    /**
     * Rulesets to apply for this request, by id. Empty means "all default
     * rulesets"; non-empty selects exactly those (and the rulesets they include).
     */
    public final List<String> ruleSets;

    private Filter(List<String> required, List<String> preferred, List<String> targetOntologies,
                   boolean includeOtherOntologies, boolean limitPerOntology, List<String> ruleSets) {
        this.required = required;
        this.preferred = preferred;
        this.targetOntologies = targetOntologies;
        this.includeOtherOntologies = includeOtherOntologies;
        this.limitPerOntology = limitPerOntology;
        this.ruleSets = ruleSets != null ? ruleSets : new ArrayList<>();
    }

    /**
     * Factory method to create a Filter from lists. Defaults
     * {@code limitPerOntology} to {@code false} (opt-in).
     */
    public static Filter fromLists(List<String> required, List<String> preferred, List<String> targetOntologies, boolean includeOtherOntologies) {
        return fromLists(required, preferred, targetOntologies, includeOtherOntologies, false);
    }

    /** Full factory with explicit {@code limitPerOntology}. */
    public static Filter fromLists(List<String> required, List<String> preferred, List<String> targetOntologies,
                                   boolean includeOtherOntologies, boolean limitPerOntology) {
        return new Filter(
            required != null ? required : new ArrayList<>(),
            preferred != null ? preferred : new ArrayList<>(),
            targetOntologies != null ? targetOntologies : new ArrayList<>(),
            includeOtherOntologies,
            limitPerOntology,
            null
        );
    }

    /** Returns a copy of this filter with the given ruleset selection (by ruleset id). */
    public Filter withRuleSets(List<String> ruleSets) {
        return new Filter(required, preferred, targetOntologies, includeOtherOntologies, limitPerOntology, ruleSets);
    }

    private static final Pattern PART = Pattern.compile("\\s*([a-zA-Z]+)\\s*:\\s*\\[(.*?)\\]\\s*");

    public static Filter parse(String raw) {
        List<String> required = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        List<String> ontologies = new ArrayList<>();

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
                    default -> throw new BadRequestResponse("Unknown filter key: " + key);
                }
            }
        }

        // v2 parse: ontologies become targetOntologies with hard filter (includeOtherOntologies=false)
        return new Filter(required, preferred, ontologies, ontologies.isEmpty(), false, null);
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
            "limitPerOntology", limitPerOntology,
            "ruleSets", ruleSets
        );
    }
}
