package uk.ac.ebi.zooma2.rules;

/**
 * The pipeline hook points at which rules fire.
 *
 * <ul>
 *   <li>{@code REWRITE}  — normalise / vary the query text before matching</li>
 *   <li>{@code OVERRIDE} — short-circuit to a curated term before retrieval</li>
 *   <li>{@code GATE}     — reject candidate terms that violate domain rules</li>
 *   <li>{@code SCORE}    — apply additive bonuses/penalties to a candidate</li>
 *   <li>{@code SELECT}   — express ontology/scope preference for final ordering</li>
 * </ul>
 */
public enum RuleStage {
    REWRITE,
    OVERRIDE,
    GATE,
    SCORE,
    SELECT
}
