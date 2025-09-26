package uk.ac.ebi.zooma2.prefix_map;

import java.util.HashMap;
import java.util.Map;

public class PrefixMap {

    Bioregistry bioregistry;
    Map<String, String> zoomaPrefixToIriPrefix = new HashMap<>();
    Map<String, String> iriPrefixToZoomaPrefix = new HashMap<>();

    public PrefixMap() {
        bioregistry = new Bioregistry();
    }

    public String iriToCurie(String iri) {

        for (String iriPrefix : iriPrefixToZoomaPrefix.keySet()) {
            if (iri.startsWith(iriPrefix)) {
                String localId = iri.substring(iriPrefix.length());
                String zoomaPrefix = iriPrefixToZoomaPrefix.get(iriPrefix);
                return zoomaPrefix + ":" + localId;
            }
        }

        return bioregistry.getCurieForUrl(iri);

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
