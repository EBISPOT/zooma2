package uk.ac.ebi.zooma2.prefix_map;

import java.util.HashMap;
import java.util.Map;

import uk.ac.ebi.zooma2.ZoomaConfig;

public class PrefixMap {

    Bioregistry bioregistry;
    Map<String, String> zoomaPrefixToIriPrefix = new HashMap<>();
    Map<String, String> iriPrefixToZoomaPrefix = new HashMap<>();

    public PrefixMap() {
        bioregistry = new Bioregistry();

        var prefix_map = ZoomaConfig.config.prefix_map;

        for (var entry : prefix_map.entrySet()) {
            String zoomaPrefix = entry.getKey();
            String iriPrefix = entry.getValue();
            zoomaPrefixToIriPrefix.put(zoomaPrefix, iriPrefix);
            iriPrefixToZoomaPrefix.put(iriPrefix, zoomaPrefix);
        }

    }

    /**
     * Short form of a term IRI when no ontology context is available: OLS's own
     * fallback, the last path or fragment segment ({@code EFO_0000400},
     * {@code CHEBI_15377}, {@code Orphanet_224}). Preserves the case OLS reports,
     * unlike a bioregistry CURIE ({@code efo:0000400}), so ids from different
     * matchers agree. With an ontology id in hand, prefer
     * {@code OlsClientRepo.olsShortForm}, which also applies the ontology's prefix.
     */
    public String iriToShortForm(String iri) {
        if (iri == null) return null;
        int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        if (cut < 0 || cut == iri.length() - 1) return null;
        return iri.substring(cut + 1);
    }

    public String iriToCurie(String iri) {

        // Try bioregistry first (more specific ontology prefixes)
        var curie = bioregistry.getCurieForUrl(iri);
        if (curie != null) {
            return curie;
        }

        // Fall back to config prefix_map (broader prefixes like obo)
        for (String iriPrefix : iriPrefixToZoomaPrefix.keySet()) {
            if (iri.startsWith(iriPrefix)) {
                String localId = iri.substring(iriPrefix.length());
                String zoomaPrefix = iriPrefixToZoomaPrefix.get(iriPrefix);
                return zoomaPrefix + ":" + localId;
            }
        }

        return null;

    }

    /**
     * Expand a short form (e.g., MONDO_0005148, EDAM_data_0006) or CURIE (e.g., MONDO:0005148) to a full IRI.
     * Returns the original string if it's already an IRI or cannot be expanded.
     *
     * <p>A short form is split at its last underscore first (the Bioregistry
     * registers EDAM's sub-namespaces as {@code edam.data}, {@code edam.topic}, ...,
     * whose URL formats are the real term IRIs), and then at each earlier
     * underscore until a prefix resolves, so a local id containing underscores
     * still expands under its ontology's prefix. Ontology prefixes resolve through
     * the Bioregistry; the config prefix map only carries non-ontology namespaces
     * (owl, rdfs, obo, ...), matched case-insensitively.
     */
    public String shortFormToIri(String shortFormOrIri) {
        if (shortFormOrIri == null) {
            return null;
        }
        // Already looks like an IRI
        if (shortFormOrIri.startsWith("http://") || shortFormOrIri.startsWith("https://")) {
            return shortFormOrIri;
        }
        // Try as CURIE (PREFIX:localId)
        if (shortFormOrIri.contains(":")) {
            try {
                String iri = curieToIri(shortFormOrIri);
                return iri != null ? iri : shortFormOrIri;
            } catch (Exception e) {
                return shortFormOrIri;
            }
        }
        // Try as short form (PREFIX_localId), splitting at the last underscore first
        for (int split = shortFormOrIri.lastIndexOf('_'); split > 0; split = shortFormOrIri.lastIndexOf('_', split - 1)) {
            String prefix = shortFormOrIri.substring(0, split);
            String localId = shortFormOrIri.substring(split + 1);
            if (localId.isEmpty()) continue;
            String iri = expand(prefix, localId);
            if (iri != null) {
                return iri;
            }
        }
        return shortFormOrIri;
    }

    private String expand(String prefix, String localId) {
        String iriPrefix = zoomaPrefixToIriPrefix.get(prefix);
        if (iriPrefix == null) {
            iriPrefix = zoomaPrefixToIriPrefix.get(prefix.toLowerCase(java.util.Locale.ROOT));
        }
        if (iriPrefix != null) {
            return iriPrefix + localId;
        }
        return bioregistry.getUrlForId(prefix, localId);
    }

    public String curieToIri(String curie) {

        var parts = curie.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CURIE: " + curie);
        }
        return expand(parts[0], parts[1]);
    }

    
}
