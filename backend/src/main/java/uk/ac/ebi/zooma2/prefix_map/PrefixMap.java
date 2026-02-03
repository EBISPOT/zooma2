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

    public String iriToShortForm(String iri) {

        // Try bioregistry first (more specific ontology prefixes)
        var curie = bioregistry.getCurieForUrl(iri);
        if(curie != null) {
            return curie.replace(":", "_");
        }

        // Fall back to config prefix_map (broader prefixes like obo)
        for (String iriPrefix : iriPrefixToZoomaPrefix.keySet()) {
            if (iri.startsWith(iriPrefix)) {
                String localId = iri.substring(iriPrefix.length());
                String zoomaPrefix = iriPrefixToZoomaPrefix.get(iriPrefix);
                String shortForm = zoomaPrefix + "_" + localId;
                // Strip obo_ prefix to match OLS short_form format (e.g., DOID_2841 not obo_DOID_2841)
                if (shortForm.startsWith("obo_")) {
                    return shortForm.substring(4);
                }
                return shortForm;
            }
        }
        
        return null;
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
     * Expand a short form (e.g., MONDO_0005148) or CURIE (e.g., MONDO:0005148) to a full IRI.
     * Returns the original string if it's already an IRI or cannot be expanded.
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
                return curieToIri(shortFormOrIri);
            } catch (Exception e) {
                return shortFormOrIri;
            }
        }
        // Try as short form (PREFIX_localId) - find last underscore
        int lastUnderscore = shortFormOrIri.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String prefix = shortFormOrIri.substring(0, lastUnderscore);
            String localId = shortFormOrIri.substring(lastUnderscore + 1);
            String iriPrefix = zoomaPrefixToIriPrefix.get(prefix);
            if (iriPrefix != null) {
                return iriPrefix + localId;
            }
            // Try bioregistry
            String iri = bioregistry.getUrlForId(prefix, localId);
            if (iri != null) {
                return iri;
            }
        }
        return shortFormOrIri;
    }

    public String curieToIri(String curie) {

        var parts = curie.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CURIE: " + curie);
        }
        String prefix = parts[0];
        String localId = parts[1];
        String iriPrefix = zoomaPrefixToIriPrefix.get(prefix);
        if (iriPrefix != null) {
            return iriPrefix + localId;
        }
        return bioregistry.getUrlForId(prefix, localId);
    }

    
}
