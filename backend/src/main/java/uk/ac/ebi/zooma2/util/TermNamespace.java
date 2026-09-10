package uk.ac.ebi.zooma2.util;

import java.util.Locale;

/**
 * Extracts the ontology namespace prefix from a term identifier, used by the
 * {@code defining_only} filter to tell an ontology's own terms apart from the
 * terms it imports.
 *
 * <p>Under the OBO conventions OLS follows, a term's local identifier carries
 * its defining ontology's prefix regardless of which ontology file it was
 * found in: ECTO's copy of a CHEBI chemical is still {@code CHEBI_9937}.
 */
public final class TermNamespace {

    private TermNamespace() {
    }

    /**
     * The lowercased namespace prefix of a term id, or {@code null} when no
     * prefix is recognisable.
     *
     * <p>Accepts short forms ({@code ECTO_9000460} → {@code ecto},
     * {@code EDAM_data_0006} → {@code edam}), CURIEs ({@code CHEBI:9937} →
     * {@code chebi}) and full IRIs (the last path or fragment segment is parsed
     * the same way). Identifiers with no prefix convention (e.g. bare SNOMED
     * numbers) yield {@code null}. Prefer passing an OLS short form over an IRI
     * where one is available: OLS short forms always carry the ontology's own
     * prefix, whereas an IRI's local part may not ({@code .../data_0006}).
     */
    /**
     * Whether a term id belongs to one of the given ontologies' own namespaces.
     * Ids with no recognisable prefix count as belonging — callers use this to
     * tighten checks that already passed an ontology-level filter, and an
     * unprefixed id gives no evidence the term is an import.
     */
    public static boolean inNamespaces(String idOrIri, java.util.Set<String> lowercaseOntologyIds) {
        String prefix = prefixOf(idOrIri);
        return prefix == null || lowercaseOntologyIds.contains(prefix);
    }

    public static String prefixOf(String idOrIri) {
        if (idOrIri == null || idOrIri.isBlank()) return null;

        String local = idOrIri;
        if (local.startsWith("http://") || local.startsWith("https://")) {
            int cut = Math.max(local.lastIndexOf('/'), local.lastIndexOf('#'));
            if (cut < 0 || cut == local.length() - 1) return null;
            local = local.substring(cut + 1);
        }

        int colon = local.indexOf(':');
        if (colon > 0) {
            return local.substring(0, colon).toLowerCase(Locale.ROOT);
        }
        // OBO and OLS prefixes never contain underscores, but local ids can
        // (EDAM_data_0006), so the prefix ends at the first underscore.
        int underscore = local.indexOf('_');
        if (underscore > 0) {
            return local.substring(0, underscore).toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
