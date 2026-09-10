package uk.ac.ebi.zooma2.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Which sources a mapping request should draw on and prefer. Built by the API
 * layers via {@link #fromLists}; the legacy V2 filter string is parsed by
 * {@code V2FilterDto}.
 */
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
}
