package uk.ac.ebi.zooma2.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.MapResult;
import uk.ac.ebi.zooma2.model.StringToMap;

/**
 * Per-query mutable workspace threaded through the mapping pipeline. One instance
 * is created per property mapped. It holds:
 * <ul>
 *   <li>the query-level inputs rules can read (text, property type, filters);</li>
 *   <li>the REWRITE outputs (effective text/type, variants, parsed ids);</li>
 *   <li>the candidate currently under evaluation during GATE/SCORE; and</li>
 *   <li>the outcome each stage writes (reject flag, score delta, ...).</li>
 * </ul>
 *
 * <p>Not thread-safe: a single context is confined to one property's processing.
 */
public class RuleContext {

    // ---- query-level inputs ----
    private final String originalText;
    private final String originalPropertyType;
    private final List<String> targetOntologies;
    private final boolean includeOtherOntologies;
    private final List<String> required;
    private final List<String> preferred;
    private final List<String> excludeTermIds;
    /** Ruleset ids selected for this request; empty means "all default rulesets". */
    private List<String> selectedRuleSets = List.of();

    // ---- REWRITE outputs (mutable) ----
    private String effectiveText;
    private String effectivePropertyType;
    private final List<String> queryVariants = new ArrayList<>();
    private final Map<String, String> parsedEmbeddedIds = new LinkedHashMap<>();

    // ---- per-candidate evaluation state ----
    private MapResult candidate;
    private boolean rejected;
    private String rejectReason;
    private double scoreDelta;

    // ---- SELECT outputs ----
    private List<String> preferredOntologyOrder;
    private boolean tieBreakByScope;

    // ---- OVERRIDE output ----
    private EmittedTerm emittedTerm;

    // ---- audit ----
    private final List<FiredRule> firedRules = new ArrayList<>();

    public RuleContext(String originalText, String originalPropertyType,
                       List<String> targetOntologies, boolean includeOtherOntologies,
                       List<String> required, List<String> preferred, List<String> excludeTermIds) {
        this.originalText = originalText;
        this.originalPropertyType = originalPropertyType;
        this.targetOntologies = targetOntologies != null ? targetOntologies : List.of();
        this.includeOtherOntologies = includeOtherOntologies;
        this.required = required != null ? required : List.of();
        this.preferred = preferred != null ? preferred : List.of();
        this.excludeTermIds = excludeTermIds != null ? excludeTermIds : List.of();
        this.effectiveText = originalText;
    }

    /** Build a context from the pipeline's per-query inputs. */
    public static RuleContext forQuery(StringToMap s, Filter f, List<String> excludeTermIds) {
        RuleContext ctx = new RuleContext(
            s != null ? s.textToMap : null,
            s != null ? s.propertyType : null,
            f != null ? f.targetOntologies : null,
            f != null && f.includeOtherOntologies,
            f != null ? f.required : null,
            f != null ? f.preferred : null,
            excludeTermIds
        );
        ctx.setSelectedRuleSets(f != null ? f.ruleSets : null);
        return ctx;
    }

    // ---- query-level accessors ----
    public String originalText() { return originalText; }
    public String effectiveText() { return effectiveText != null ? effectiveText : originalText; }
    public void setEffectiveText(String t) { this.effectiveText = t; }

    public List<String> targetOntologies() { return targetOntologies; }
    public boolean includeOtherOntologies() { return includeOtherOntologies; }
    public List<String> required() { return required; }
    public List<String> preferred() { return preferred; }
    public List<String> excludeTermIds() { return excludeTermIds; }
    public List<String> selectedRuleSets() { return selectedRuleSets; }
    public void setSelectedRuleSets(List<String> rs) { this.selectedRuleSets = rs != null ? rs : List.of(); }

    /** Effective property type, normalised: lower-cased; blank/null becomes "unspecified". */
    public String propertyType() {
        String pt = effectivePropertyType != null ? effectivePropertyType : originalPropertyType;
        if (pt == null || pt.isBlank()) return "unspecified";
        return pt.trim().toLowerCase(Locale.ROOT);
    }
    public void setEffectivePropertyType(String pt) { this.effectivePropertyType = pt; }
    /** The raw effective property type set by a REWRITE rule, or {@code null} if unset. */
    public String effectivePropertyTypeOrNull() { return effectivePropertyType; }

    public List<String> queryVariants() { return queryVariants; }
    public void addVariant(String v) {
        if (v != null && !v.isBlank() && !v.equals(effectiveText()) && !queryVariants.contains(v)) {
            queryVariants.add(v);
        }
    }
    public Map<String, String> parsedEmbeddedIds() { return parsedEmbeddedIds; }

    /** Tokens of the effective query text (lower-case, split on non-alphanumeric). */
    public Set<String> queryTokens() { return tokenize(effectiveText()); }

    static Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String tok : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!tok.isEmpty()) out.add(tok);
        }
        return out;
    }

    // ---- candidate evaluation ----
    public MapResult candidate() { return candidate; }

    /** Begin evaluating a candidate: set it and clear per-candidate outcome state. */
    public void beginCandidate(MapResult c) {
        this.candidate = c;
        this.rejected = false;
        this.rejectReason = null;
        this.scoreDelta = 0.0;
    }

    public void reject(String reason) { this.rejected = true; this.rejectReason = reason; }
    public boolean isRejected() { return rejected; }
    public String rejectReason() { return rejectReason; }

    public void addScoreDelta(double d) { this.scoreDelta += d; }
    public double scoreDelta() { return scoreDelta; }

    // ---- SELECT ----
    public void setPreferredOntologyOrder(List<String> order) { this.preferredOntologyOrder = order; }
    public List<String> preferredOntologyOrder() { return preferredOntologyOrder; }
    public void setTieBreakByScope(boolean b) { this.tieBreakByScope = b; }
    public boolean tieBreakByScope() { return tieBreakByScope; }

    // ---- OVERRIDE ----
    public void setEmittedTerm(EmittedTerm t) { this.emittedTerm = t; }
    public EmittedTerm emittedTerm() { return emittedTerm; }

    // ---- audit ----
    public List<FiredRule> firedRules() { return firedRules; }
    public void recordFired(RuleStage stage, String ruleId, String detail) {
        firedRules.add(new FiredRule(stage, ruleId, detail));
    }

    /** Candidate's ontology prefix (e.g. "uberon" from "UBERON_0001234"), lower-case. */
    public String candidateOntologyPrefix() {
        if (candidate == null) return null;
        String id = candidate.ontologyTermID;
        if (id != null) {
            int sep = indexOfSeparator(id);
            if (sep > 0) return id.substring(0, sep).toLowerCase(Locale.ROOT);
        }
        return candidate.ontologyURI != null ? candidate.ontologyURI.toLowerCase(Locale.ROOT) : null;
    }

    private static int indexOfSeparator(String id) {
        int c = id.indexOf(':');
        int u = id.indexOf('_');
        if (c < 0) return u;
        if (u < 0) return c;
        return Math.min(c, u);
    }

    /** Canonical term id for comparison: upper-cased, ':' normalised to '_'. */
    public static String canonicalId(String id) {
        if (id == null) return null;
        return id.trim().toUpperCase(Locale.ROOT).replace(':', '_');
    }

    /** A term emitted directly by an OVERRIDE rule (curated shortcut). */
    public record EmittedTerm(String id, String label, double confidence) {}
}
