package uk.ac.ebi.zooma2.rules;

import java.util.List;

/**
 * A single declarative rule: when {@code when} holds (or is absent), apply each
 * action in {@code then} at the given {@code stage}. Higher {@code priority} fires
 * first within a stage; equal priorities keep ruleset/file order.
 *
 * <p>Populated directly by Gson from JSON; {@code when} and the elements of
 * {@code then} use the custom deserializers registered in {@link RuleSetLoader}.
 */
public class Rule {
    public String id;
    public RuleStage stage;
    public int priority;       // higher fires first; default 0
    public Condition when;     // null ⇒ always matches
    public List<Action> then;  // may be empty
}
