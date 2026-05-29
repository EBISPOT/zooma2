package uk.ac.ebi.zooma2.rules;

/**
 * Audit record of a single rule firing, accumulated on the {@link RuleContext}
 * so a mapping decision can be explained after the fact.
 *
 * @param stage  the stage at which the rule fired
 * @param ruleId the id of the rule that fired
 * @param detail context for the firing (the candidate term id, or the query text)
 */
public record FiredRule(RuleStage stage, String ruleId, String detail) {}
