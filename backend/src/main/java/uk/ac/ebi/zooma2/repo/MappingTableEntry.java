
package uk.ac.ebi.zooma2.repo;

public class MappingTableEntry {

    public String databaseId;
    public String databaseUrl;
    public String study;
    public String bioentity;
    public String propertyType;
    public String propertyValue;
    public String semanticTag;
    public String annotator;
    public String annotationDate;
    
    // True if this entry was found via embedding/vector search rather than exact match
    public boolean fromEmbeddingSearch = false;
    // The original search term (if different from propertyValue due to semantic match)
    public String originalSearchTerm;
    // Similarity score from vector search (0.0 to 1.0)
    public Double similarityScore;
    
}
