package uk.ac.ebi.zooma2.rules;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * A named, composable collection of rules.
 *
 * <p>{@code appliesWhen} is a query-level predicate that decides whether this
 * ruleset is active for a given query (null ⇒ always). {@code includes} names other
 * rulesets to compose in: when this ruleset is active, the rules of every included
 * ruleset are active too (transitively, cycle-safe).
 *
 * <p>{@code default} controls whether the ruleset applies when a request does not
 * name a selection. Defaults to {@code true}; set {@code "default": false} to make
 * a ruleset opt-in — applied only when explicitly selected via the request's
 * {@code ruleSets} (directly, or through another selected ruleset's {@code includes}).
 */
public class RuleSet {
    public String id;
    public String domain;          // informational: "trait" | "analyte" | "any"
    @SerializedName("default")
    public Boolean defaultActive;  // null/true ⇒ applies by default; false ⇒ opt-in only
    public Condition appliesWhen;  // query-level predicate; null ⇒ always
    public List<String> includes;  // ids of other rulesets to compose in
    public List<Rule> rules;

    public boolean isDefault() {
        return defaultActive == null || defaultActive;
    }
}
