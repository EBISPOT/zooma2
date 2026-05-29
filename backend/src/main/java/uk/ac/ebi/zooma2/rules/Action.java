package uk.ac.ebi.zooma2.rules;

/**
 * A declarative action applied to the {@link RuleContext} when a rule fires.
 *
 * <p>Actions never mutate pipeline objects directly; they record their effect on
 * the context (reject flag, score delta, rewritten text, preferred ordering, ...)
 * and the relevant pipeline stage then consumes that outcome. This keeps rule
 * evaluation pure with respect to the wider system and fully testable.
 *
 * <p>The concrete vocabulary lives in {@link Actions}; instances are produced from
 * JSON by {@link RuleSetLoader}'s deserializer (one discriminator key per action).
 */
public interface Action {
    void apply(RuleContext ctx);
}
