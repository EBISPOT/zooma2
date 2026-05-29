package uk.ac.ebi.zooma2.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.model.MapResult;

/**
 * Evaluates declarative rulesets against a {@link RuleContext}. The engine is
 * generic and deterministic — all domain knowledge lives in the JSON rulesets.
 *
 * <p>For a query it computes the active rulesets (those whose {@code appliesWhen}
 * matches, plus everything they {@code include}, transitively and cycle-safely),
 * then for a given {@link RuleStage} fires every matching rule in descending
 * priority order, letting each action mutate the context.
 */
public class RuleEngine {

    private final Map<String, RuleSet> byId = new LinkedHashMap<>();
    private final List<RuleSet> all;

    public RuleEngine(List<RuleSet> ruleSets) {
        this.all = ruleSets != null ? ruleSets : List.of();
        for (RuleSet rs : this.all) {
            if (rs.id != null) byId.put(rs.id, rs);
        }
    }

    public static RuleEngine empty() { return new RuleEngine(List.of()); }

    public boolean isEmpty() { return all.isEmpty(); }

    public int ruleSetCount() { return all.size(); }

    /**
     * GATE helper: returns {@code true} if any active GATE rule rejects this
     * candidate. The firing rule and reason are recorded on the context.
     */
    public boolean shouldReject(RuleContext ctx, MapResult candidate) {
        if (all.isEmpty()) return false;
        ctx.beginCandidate(candidate);
        fire(RuleStage.GATE, ctx);
        return ctx.isRejected();
    }

    /**
     * SCORE helper: returns the net additive score delta from active SCORE rules
     * for this candidate (positive boosts minus penalties). The caller composes
     * this onto the candidate's ranking score.
     */
    public double scoreDelta(RuleContext ctx, MapResult candidate) {
        if (all.isEmpty()) return 0.0;
        ctx.beginCandidate(candidate);
        fire(RuleStage.SCORE, ctx);
        return ctx.scoreDelta();
    }

    /**
     * SELECT helper: fires the query-level SELECT rules and returns the preferred
     * ontology ordering they declare, or {@code null} if none applies.
     */
    public List<String> selectOntologyOrder(RuleContext ctx) {
        if (all.isEmpty()) return null;
        ctx.beginCandidate(null);
        fire(RuleStage.SELECT, ctx);
        return ctx.preferredOntologyOrder();
    }

    /**
     * OVERRIDE helper: fires the query-level OVERRIDE rules and returns the curated
     * term one of them emitted (a phrase→term shortcut), or {@code null} if none
     * applies. When non-null the caller can short-circuit retrieval entirely.
     */
    public RuleContext.EmittedTerm overrideTerm(RuleContext ctx) {
        if (all.isEmpty()) return null;
        ctx.beginCandidate(null);
        fire(RuleStage.OVERRIDE, ctx);
        return ctx.emittedTerm();
    }

    /**
     * Fire all rules for {@code stage} whose ruleset is active and whose
     * {@code when} matches, in descending priority order. Actions mutate the
     * context; the caller reads the resulting outcome. GATE short-circuits on the
     * first rejection.
     */
    public void fire(RuleStage stage, RuleContext ctx) {
        if (all.isEmpty()) return;
        for (Rule r : activeRules(stage, ctx)) {
            if (r.when == null || r.when.matches(ctx)) {
                ctx.recordFired(stage, r.id,
                    ctx.candidate() != null ? ctx.candidate().ontologyTermID : ctx.effectiveText());
                if (r.then != null) for (Action a : r.then) a.apply(ctx);
                if (stage == RuleStage.GATE && ctx.isRejected()) return;
            }
        }
    }

    /** Collect rules for a stage from all active rulesets, sorted by priority desc (stable). */
    List<Rule> activeRules(RuleStage stage, RuleContext ctx) {
        List<Rule> out = new ArrayList<>();
        for (String id : activeRuleSetIds(ctx)) {
            RuleSet rs = byId.get(id);
            if (rs == null || rs.rules == null) continue;
            for (Rule r : rs.rules) {
                if (r.stage == stage) out.add(r);
            }
        }
        out.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return out;
    }

    /** Transitive closure of rulesets active for this query (entry = {@code appliesWhen} matches). */
    Set<String> activeRuleSetIds(RuleContext ctx) {
        Set<String> result = new LinkedHashSet<>();
        List<String> selected = ctx.selectedRuleSets();
        boolean hasSelection = selected != null && !selected.isEmpty();
        Set<String> selectedLower = hasSelection
            ? selected.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet())
            : Set.of();
        for (RuleSet rs : all) {
            if (rs.id == null) continue;
            // Entry rulesets are the explicitly selected ones; or, when the request
            // names no selection, every ruleset that applies by default.
            boolean isEntry = hasSelection
                ? selectedLower.contains(rs.id.toLowerCase(Locale.ROOT))
                : rs.isDefault();
            if (!isEntry) continue;
            // appliesWhen still scopes an entry ruleset to the query; includes are
            // pulled in unconditionally (composition) by addWithIncludes.
            if (rs.appliesWhen == null || rs.appliesWhen.matches(ctx)) {
                addWithIncludes(rs.id, result);
            }
        }
        return result;
    }

    private void addWithIncludes(String id, Set<String> acc) {
        if (id == null || acc.contains(id)) return; // cycle / duplicate guard
        RuleSet rs = byId.get(id);
        if (rs == null) return;
        acc.add(id);
        if (rs.includes != null) {
            for (String inc : rs.includes) addWithIncludes(inc, acc);
        }
    }
}
