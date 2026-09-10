package uk.ac.ebi.zooma2.repo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * OLS's own short-form convention, so ids derived locally (for text-tagger hits,
 * which carry only an IRI) look exactly like the ids OLS reports for the same
 * term through its search and term endpoints.
 *
 * <p>Mirrors {@code ShortFormAnnotator.extractShortForm} in OLS4: the IRI is
 * matched against the ontology's configured base URIs plus the OBO-style
 * {@code http://purl.obolibrary.org/obo/<PreferredPrefix>_}, and the remainder is
 * prefixed with the preferred prefix ({@code EFO_0000400}, {@code mesh_D000686},
 * {@code ORDO_224}); an IRI matching no base falls back to its last path or
 * fragment segment ({@code CHEBI_15377}). OLS also supports a per-ontology
 * {@code shortFormExtractionPattern}, which its API does not expose; the few
 * ontologies using it will differ, which is harmless because results are
 * deduplicated and excluded by IRI, not by short form.
 */
public final class OlsShortForms {

    private static final String OBO_BASE = "http://purl.obolibrary.org/obo/";

    private OlsShortForms() {
    }

    /**
     * @param ontologyId      OLS ontology id (used when no preferred prefix is configured, as OLS does)
     * @param preferredPrefix configured prefix, or {@code null}
     * @param baseUris        configured base URIs, or {@code null}
     * @param iri             term IRI
     * @return the OLS-style short form, or {@code null} if {@code iri} is null
     */
    public static String shortForm(String ontologyId, String preferredPrefix, Collection<String> baseUris, String iri) {
        if (iri == null) return null;
        if (iri.startsWith("urn:")) return iri.substring(4);

        String prefix = preferredPrefix != null && !preferredPrefix.isEmpty()
            ? preferredPrefix
            : (ontologyId != null ? ontologyId.toUpperCase(Locale.ROOT) : null);

        List<String> bases = new ArrayList<>();
        if (baseUris != null) bases.addAll(baseUris);
        if (prefix != null) bases.add(OBO_BASE + prefix + "_");
        // OLS iterates an unordered set; the longest match is the deterministic choice
        // that agrees with it whenever only one base applies.
        bases.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String base : bases) {
            if (base != null && !base.isEmpty() && iri.startsWith(base) && prefix != null) {
                return prefix + "_" + iri.substring(base.length());
            }
        }
        return localPart(iri);
    }

    /** The last path or fragment segment of an IRI, OLS's fallback short form. */
    public static String localPart(String iri) {
        if (iri == null) return null;
        int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        if (cut < 0) return iri;
        String tail = iri.substring(cut + 1);
        return tail.isEmpty() ? null : tail;
    }
}
