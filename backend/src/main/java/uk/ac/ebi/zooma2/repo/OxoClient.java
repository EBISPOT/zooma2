package uk.ac.ebi.zooma2.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Collectors;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Client for the OXO (Ontology Xref Service) API.
 * OXO provides mappings between ontology terms across different ontologies.
 */
public class OxoClient {

    private static final String OXO_API_BASE = "https://www.ebi.ac.uk/spot/oxo/api";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OxoClient() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Search for cross-references to map term IDs to target ontologies.
     * 
     * @param termIds List of term IDs to search for (e.g., ["DOID:162"])
     * @param targetOntologies List of target ontology prefixes (e.g., ["MONDO", "EFO"])
     * @param distance Maximum mapping distance (1 = direct mappings, 2 = one hop, etc.)
     * @return List of OXO mappings
     */
    public List<OxoMapping> search(List<String> termIds, List<String> targetOntologies, int distance) {
        if (termIds == null || termIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // Uppercase prefixes in term IDs (e.g., "ncit:C195298" -> "NCIT:C195298")
            List<String> normalizedTermIds = termIds.stream()
                .map(id -> {
                    int colonIndex = id.indexOf(':');
                    if (colonIndex > 0) {
                        String prefix = id.substring(0, colonIndex).toUpperCase();
                        String localId = id.substring(colonIndex + 1);
                        return prefix + ":" + localId;
                    }
                    return id;
                })
                .collect(Collectors.toList());
            
            // Uppercase target ontology prefixes
            List<String> normalizedTargets = targetOntologies != null 
                ? targetOntologies.stream().map(String::toUpperCase).collect(Collectors.toList())
                : Collections.emptyList();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", normalizedTermIds);
            requestBody.put("mappingTarget", normalizedTargets);
            requestBody.put("distance", distance);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OXO_API_BASE + "/search"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseOxoResponse(response.body());
            } else {
                System.err.println("OXO API error: " + response.statusCode() + " - " + response.body());
                return Collections.emptyList();
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error calling OXO API: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse the OXO API response and extract mappings.
     */
    private List<OxoMapping> parseOxoResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embedded = root.path("_embedded");
            JsonNode searchResults = embedded.path("searchResults");

            List<OxoMapping> mappings = new ArrayList<>();

            if (searchResults.isArray()) {
                for (JsonNode result : searchResults) {
                    String queryId = result.path("queryId").asText();
                    String queryLabel = result.path("label").asText(null);
                    
                    JsonNode mappingResponseList = result.path("mappingResponseList");
                    if (mappingResponseList.isArray()) {
                        for (JsonNode mappingResponse : mappingResponseList) {
                            String targetId = mappingResponse.path("curie").asText();
                            String targetLabel = mappingResponse.path("label").asText(null);
                            String targetPrefix = mappingResponse.path("targetPrefix").asText(null);
                            int distance = mappingResponse.path("distance").asInt(1);

                            OxoMapping mapping = new OxoMapping();
                            mapping.sourceId = queryId;
                            mapping.sourceLabel = queryLabel;
                            mapping.targetId = targetId;
                            mapping.targetLabel = targetLabel;
                            mapping.targetPrefix = targetPrefix;
                            mapping.distance = distance;

                            mappings.add(mapping);
                        }
                    }
                }
            }

            return mappings;
        } catch (Exception e) {
            System.err.println("Error parsing OXO response: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Represents a single OXO mapping between two terms.
     */
    public static class OxoMapping {
        public String sourceId;
        public String sourceLabel;
        public String targetId;
        public String targetLabel;
        public String targetPrefix;
        public int distance;

        @Override
        public String toString() {
            return String.format("%s (%s) -> %s (%s) [distance=%d]",
                sourceId, sourceLabel, targetId, targetLabel, distance);
        }
    }

    /**
     * Extract ontology prefixes from term IDs.
     * E.g., "MONDO:0005015" -> "MONDO"
     */
    public static List<String> extractPrefixes(List<String> termIds) {
        if (termIds == null) {
            return Collections.emptyList();
        }
        
        return termIds.stream()
            .map(id -> {
                if (id.contains(":")) {
                    return id.substring(0, id.indexOf(":"));
                }
                return null;
            })
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    }
}
