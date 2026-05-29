package uk.ac.ebi.zooma2.rules;

/**
 * A predicate over a {@link RuleContext}. Conditions read query-level state
 * (text, property type, target ontologies) and/or the candidate currently under
 * evaluation. They are pure and side-effect free.
 *
 * <p>The concrete vocabulary lives in {@link Conditions}; instances are produced
 * from JSON by {@link RuleSetLoader}'s deserializer (one discriminator key per
 * condition). There is deliberately no expression/script evaluation.
 */
public interface Condition {
    boolean matches(RuleContext ctx);
}
