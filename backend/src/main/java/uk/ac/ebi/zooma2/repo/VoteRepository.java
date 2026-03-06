package uk.ac.ebi.zooma2.repo;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores user votes (thumbs-up / thumbs-down) on mapping results.
 * Uses the unified ZoomaDatabase (votes table).
 */
public class VoteRepository {

    private final ZoomaDatabase db;

    public VoteRepository(ZoomaDatabase db) {
        this.db = db;
    }

    public void recordVote(String propertyValue, String propertyType, String termId,
                           String termLabel, String ontology, String vote) {
        String sql = "INSERT INTO votes (property_value, property_type, term_id, term_label, ontology, vote) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
        try (var conn = db.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, propertyValue);
            ps.setString(2, propertyType);
            ps.setString(3, termId);
            ps.setString(4, termLabel);
            ps.setString(5, ontology);
            ps.setString(6, vote);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record vote", e);
        }
    }

    public List<Vote> getVotes(String propertyValue) {
        String sql = "SELECT id, property_value, property_type, term_id, term_label, ontology, vote, created_at "
            + "FROM votes WHERE property_value = ? ORDER BY created_at DESC";
        List<Vote> votes = new ArrayList<>();
        try (var conn = db.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, propertyValue);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    votes.add(new Vote(
                        rs.getLong("id"),
                        rs.getString("property_value"),
                        rs.getString("property_type"),
                        rs.getString("term_id"),
                        rs.getString("term_label"),
                        rs.getString("ontology"),
                        rs.getString("vote"),
                        rs.getString("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get votes", e);
        }
        return votes;
    }

    public record Vote(
        long id,
        String propertyValue,
        String propertyType,
        String termId,
        String termLabel,
        String ontology,
        String vote,
        String createdAt
    ) {}
}
