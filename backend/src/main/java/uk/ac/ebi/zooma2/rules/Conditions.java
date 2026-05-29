package uk.ac.ebi.zooma2.rules;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import uk.ac.ebi.zooma2.model.MapResult;

/**
 * The fixed {@link Condition} vocabulary. Each concrete class corresponds to one
 * JSON discriminator key handled by {@link RuleSetLoader}'s condition deserializer.
 * All candidate-reading conditions are null-safe (no candidate ⇒ {@code false}),
 * so they are also safe to evaluate at query-level stages.
 */
public final class Conditions {
    private Conditions() {}

    // ---- logical composition ----

    public static final class AllOf implements Condition {
        final List<Condition> items;
        public AllOf(List<Condition> items) { this.items = items; }
        public boolean matches(RuleContext ctx) {
            for (Condition c : items) if (!c.matches(ctx)) return false;
            return true;
        }
    }

    public static final class AnyOf implements Condition {
        final List<Condition> items;
        public AnyOf(List<Condition> items) { this.items = items; }
        public boolean matches(RuleContext ctx) {
            for (Condition c : items) if (c.matches(ctx)) return true;
            return false;
        }
    }

    public static final class Not implements Condition {
        final Condition inner;
        public Not(Condition inner) { this.inner = inner; }
        public boolean matches(RuleContext ctx) { return !inner.matches(ctx); }
    }

    // ---- query-level ----

    public static final class QueryMatches implements Condition {
        final Pattern pattern;
        public QueryMatches(String regex) { this.pattern = Pattern.compile(regex); }
        public boolean matches(RuleContext ctx) {
            String t = ctx.effectiveText();
            return t != null && pattern.matcher(t).find();
        }
    }

    public static final class QueryContainsAnyToken implements Condition {
        final Set<String> tokens;
        public QueryContainsAnyToken(List<String> toks) { this.tokens = lower(toks); }
        public boolean matches(RuleContext ctx) {
            Set<String> q = ctx.queryTokens();
            for (String t : tokens) if (q.contains(t)) return true;
            return false;
        }
    }

    public static final class QueryContainsAllTokens implements Condition {
        final Set<String> tokens;
        public QueryContainsAllTokens(List<String> toks) { this.tokens = lower(toks); }
        public boolean matches(RuleContext ctx) { return ctx.queryTokens().containsAll(tokens); }
    }

    public static final class PropertyTypeIn implements Condition {
        final Set<String> types;
        final boolean wildcard;
        public PropertyTypeIn(List<String> ts) {
            this.wildcard = ts.stream().anyMatch(s -> "*".equals(s.trim()));
            this.types = lower(ts);
        }
        public boolean matches(RuleContext ctx) { return wildcard || types.contains(ctx.propertyType()); }
    }

    // ---- candidate-level ----

    public static final class CandidateOntologyIn implements Condition {
        final Set<String> prefixes;
        public CandidateOntologyIn(List<String> p) { this.prefixes = lower(p); }
        public boolean matches(RuleContext ctx) {
            String onto = ctx.candidateOntologyPrefix();
            return onto != null && prefixes.contains(onto);
        }
    }

    public static final class CandidateIdIn implements Condition {
        final Set<String> ids;
        public CandidateIdIn(List<String> in) {
            this.ids = in.stream().map(RuleContext::canonicalId).collect(Collectors.toSet());
        }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            if (c == null || c.ontologyTermID == null) return false;
            return ids.contains(RuleContext.canonicalId(c.ontologyTermID));
        }
    }

    public static final class CandidateLabelMatches implements Condition {
        final Pattern pattern;
        public CandidateLabelMatches(String regex) { this.pattern = Pattern.compile(regex); }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            return c != null && c.ontologyTermLabel != null && pattern.matcher(c.ontologyTermLabel).find();
        }
    }

    public static final class CandidateLabelContainsAny implements Condition {
        final List<String> needles;
        public CandidateLabelContainsAny(List<String> n) {
            this.needles = n.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
        }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            if (c == null || c.ontologyTermLabel == null) return false;
            String label = c.ontologyTermLabel.toLowerCase(Locale.ROOT);
            for (String n : needles) if (label.contains(n)) return true;
            return false;
        }
    }

    public static final class CandidateIsObsolete implements Condition {
        final boolean expected;
        public CandidateIsObsolete(boolean expected) { this.expected = expected; }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            boolean obs = c != null && c.ontologyTermLabel != null
                && c.ontologyTermLabel.toLowerCase(Locale.ROOT).startsWith("obsolete");
            return obs == expected;
        }
    }

    public static final class CandidateMethodIn implements Condition {
        final Set<String> methods;
        public CandidateMethodIn(List<String> m) { this.methods = lower(m); }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            if (c == null || c.mappingProvenance == null || c.mappingProvenance.isEmpty()) return false;
            String method = c.mappingProvenance.get(0).method;
            return method != null && methods.contains(method.toLowerCase(Locale.ROOT));
        }
    }

    public static final class CandidateScoreBelow implements Condition {
        final double threshold;
        public CandidateScoreBelow(double t) { this.threshold = t; }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            return c != null && c.mappingConfidence < threshold;
        }
    }

    public static final class CandidateScoreAbove implements Condition {
        final double threshold;
        public CandidateScoreAbove(double t) { this.threshold = t; }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            return c != null && c.mappingConfidence > threshold;
        }
    }

    public static final class TokenOverlapBelow implements Condition {
        final double threshold;
        public TokenOverlapBelow(double t) { this.threshold = t; }
        public boolean matches(RuleContext ctx) {
            MapResult c = ctx.candidate();
            if (c == null || c.ontologyTermLabel == null) return false;
            Set<String> q = ctx.queryTokens();
            if (q.isEmpty()) return false;
            Set<String> labelToks = RuleContext.tokenize(c.ontologyTermLabel);
            long inter = q.stream().filter(labelToks::contains).count();
            return ((double) inter / q.size()) < threshold;
        }
    }

    private static Set<String> lower(List<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
