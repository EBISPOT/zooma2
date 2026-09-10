package uk.ac.ebi.zooma2.util;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Comparison forms of a term identifier, so that ids can be matched however a
 * client or matcher happened to render them: a short form in any casing
 * ({@code EFO_0000400}, {@code efo_0000400}), a CURIE, an OLS-style id
 * ({@code mesh_D000686}) or the IRI itself. Expanding a short form through the
 * Bioregistry is not enough on its own because its canonical URL is not OLS's
 * IRI for every ontology (MeSH, SNOMED), so both sides are compared on every
 * form they can take.
 */
public final class TermIds {

    private TermIds() {
    }

    /** Lower-cased comparison forms of a term: the id, the IRI, and the IRI's prefixed local part. */
    public static Set<String> forms(String id, String iri) {
        Set<String> forms = new HashSet<>();
        addForms(forms, id, iri);
        return forms;
    }

    public static void addForms(Set<String> forms, String id, String iri) {
        if (id != null && !id.isBlank()) forms.add(id.toLowerCase(Locale.ROOT));
        if (iri != null && !iri.isBlank()) {
            forms.add(iri.toLowerCase(Locale.ROOT));
            int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
            String local = cut >= 0 ? iri.substring(cut + 1) : iri;
            // Only a prefixed local part (EFO_0000400) identifies a term on its own;
            // a bare number could collide across ontologies.
            if (local.contains("_") || local.contains(":")) forms.add(local.toLowerCase(Locale.ROOT));
        }
    }

    /** True if any comparison form of the term ({@code id}, {@code iri}) is in {@code excludedForms}. */
    public static boolean matchesAny(Set<String> excludedForms, String id, String iri) {
        if (excludedForms == null || excludedForms.isEmpty()) return false;
        for (String form : forms(id, iri)) {
            if (excludedForms.contains(form)) return true;
        }
        return false;
    }
}
