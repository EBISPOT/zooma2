package uk.ac.ebi.zooma2.rules;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fixed {@link Action} vocabulary. Each concrete class corresponds to one JSON
 * discriminator key handled by {@link RuleSetLoader}'s action deserializer.
 *
 * <p>Only {@code reject} (GATE) is consumed by the pipeline today; the remaining
 * actions record their effect on the {@link RuleContext} and are wired into their
 * respective stages in later phases (REWRITE / SCORE / SELECT / OVERRIDE).
 */
public final class Actions {
    private Actions() {}

    /** GATE: drop the current candidate. */
    public static final class Reject implements Action {
        final String reason;
        public Reject(String reason) { this.reason = reason; }
        public void apply(RuleContext ctx) { ctx.reject(reason); }
    }

    /** SCORE: add a positive delta to the candidate's ranking score. */
    public static final class Boost implements Action {
        final double amount;
        public Boost(double amount) { this.amount = amount; }
        public void apply(RuleContext ctx) { ctx.addScoreDelta(Math.abs(amount)); }
    }

    /** SCORE: subtract from the candidate's ranking score. */
    public static final class Penalize implements Action {
        final double amount;
        public Penalize(double amount) { this.amount = amount; }
        public void apply(RuleContext ctx) { ctx.addScoreDelta(-Math.abs(amount)); }
    }

    /** REWRITE: regex-replace within the effective query text. */
    public static final class Normalize implements Action {
        final Pattern pattern;
        final String replacement;
        public Normalize(String pattern, String replacement) {
            this.pattern = Pattern.compile(pattern);
            this.replacement = replacement != null ? replacement : "";
        }
        public void apply(RuleContext ctx) {
            String t = ctx.effectiveText();
            if (t != null) ctx.setEffectiveText(pattern.matcher(t).replaceAll(replacement).trim());
        }
    }

    /** REWRITE: replace the effective text via a {@link NamedTransforms named transform}. */
    public static final class ApplyTransform implements Action {
        final String name;
        final Map<String, String> params;
        public ApplyTransform(String name, Map<String, String> params) { this.name = name; this.params = params; }
        public void apply(RuleContext ctx) { ctx.setEffectiveText(NamedTransforms.apply(name, ctx.effectiveText(), params)); }
    }

    /** REWRITE: add a query variant produced by a named transform of the effective text. */
    public static final class AddVariant implements Action {
        final String transform;
        public AddVariant(String transform) { this.transform = transform; }
        public void apply(RuleContext ctx) { ctx.addVariant(NamedTransforms.apply(transform, ctx.effectiveText(), null)); }
    }

    /**
     * REWRITE: apply an ordered sequence of regex substitutions to a copy of the
     * effective text and add the result as a query variant (the primary text is
     * left untouched). This faithfully mirrors the script's normalisation tables
     * (e.g. {@code normalize_trait_semantic_text}), which produce a normalised
     * form that is searched alongside the original rather than replacing it.
     */
    public static final class AddNormalizedVariant implements Action {
        public record Sub(Pattern pattern, String replacement) {}
        final List<Sub> subs;
        /** Lower-case the text before applying subs, mirroring a script normalizer that
         *  runs on {@code norm_key} (lower-cased) input rather than raw text. */
        final boolean lowercaseFirst;
        public AddNormalizedVariant(List<Sub> subs, boolean lowercaseFirst) {
            this.subs = subs;
            this.lowercaseFirst = lowercaseFirst;
        }
        public void apply(RuleContext ctx) {
            String text = ctx.effectiveText();
            if (text == null) return;
            if (lowercaseFirst) text = text.toLowerCase(java.util.Locale.ROOT);
            for (Sub s : subs) text = s.pattern.matcher(text).replaceAll(s.replacement);
            ctx.addVariant(NamedTransforms.apply("collapseDuplicateTokens", text, null));
        }
    }

    /** REWRITE: override the effective property type (kept off MapResult to preserve grouping). */
    public static final class SetEffectiveType implements Action {
        final String value;
        public SetEffectiveType(String value) { this.value = value; }
        public void apply(RuleContext ctx) { ctx.setEffectivePropertyType(value); }
    }

    /** REWRITE: parse embedded ontology/code ids from the text (implemented in the REWRITE phase). */
    public static final class ParseEmbeddedId implements Action {
        public void apply(RuleContext ctx) { /* no-op until REWRITE phase */ }
    }

    /** OVERRIDE: short-circuit to a curated term. */
    public static final class EmitTerm implements Action {
        final String id;
        final String label;
        final double confidence;
        public EmitTerm(String id, String label, double confidence) { this.id = id; this.label = label; this.confidence = confidence; }
        public void apply(RuleContext ctx) { ctx.setEmittedTerm(new RuleContext.EmittedTerm(id, label, confidence)); }
    }

    /** SELECT: declare the preferred ontology ordering for final selection. */
    public static final class PreferOntologyOrder implements Action {
        final List<String> order;
        public PreferOntologyOrder(List<String> order) { this.order = order; }
        public void apply(RuleContext ctx) { ctx.setPreferredOntologyOrder(order); }
    }

    /** SELECT: tie-break by synonym scope (requires scope plumbing; see Phase 6). */
    public static final class TieBreakByScope implements Action {
        public void apply(RuleContext ctx) { ctx.setTieBreakByScope(true); }
    }
}
