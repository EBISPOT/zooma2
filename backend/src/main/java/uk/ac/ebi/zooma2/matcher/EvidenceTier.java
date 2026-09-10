package uk.ac.ebi.zooma2.matcher;

import java.util.Comparator;
import java.util.List;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingCandidateDto;
import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.MapResult;

/**
 * The single table that turns a piece of mapping evidence into a confidence.
 *
 * <p>Confidence is a property of the <em>evidence class</em>, not of the matcher
 * that happened to find it: an exact label match is worth the same whether the
 * text tagger or the fuzzy search endpoint reported it. Every matcher therefore
 * looks its match type up here instead of carrying its own numbers, and every
 * consumer that needs to know "is this a full match?" or "is this lexically
 * grounded?" derives the answer from a result's provenance via {@link #of(List)}
 * rather than from a score threshold, which embedding scores can cross.
 *
 * <p>Declaration order is trust order: an earlier tier outranks a later one when
 * confidences tie (curated-full and label-full both score 1.0, but a
 * curator-verified mapping must rank first), see {@link #resultRanking()}.
 */
public enum EvidenceTier {

    /** The whole query matched a curated (human-verified) mapping. */
    CURATED_FULL(1.0, true),
    /** The whole query matched a term's primary label. */
    LABEL_FULL(1.0, true),
    /** The whole query matched a term synonym. */
    SYNONYM_FULL(0.9, true),
    /** Only part of the query matched (substring hit or fuzzy token overlap). */
    PARTIAL(0.89, false),
    /** Embedding similarity: never allowed to outrank a synonym full match. */
    EMBEDDING(0.89, false),
    /** No recognisable first-hand evidence (e.g. a bare cross-reference step). */
    NONE(0.0, false);

    /** Highest confidence this tier can produce. */
    public final double maxConfidence;
    private final boolean fullMatch;

    EvidenceTier(double maxConfidence, boolean fullMatch) {
        this.maxConfidence = maxConfidence;
        this.fullMatch = fullMatch;
    }

    /**
     * Confidence for a match in this tier. Full-match tiers score their fixed
     * value; partial and embedding tiers scale {@code similarity} (coverage or
     * embedding score, 0..1) by the tier's cap.
     */
    public double confidence(double similarity) {
        return fullMatch ? maxConfidence : similarity * maxConfidence;
    }

    /** True if the whole query text was matched (curated, label or synonym). */
    public boolean isFullMatch() {
        return fullMatch;
    }

    /**
     * True for the tiers strong enough that the engine need not search any
     * further: curated full match and primary-label full match. A synonym full
     * match is deliberately excluded so that other ontologies still get the
     * chance to offer a label match.
     */
    public boolean isDefinitive() {
        return this == CURATED_FULL || this == LABEL_FULL;
    }

    /** Lower is better; used to break confidence ties in favour of stronger evidence. */
    public int rank() {
        return ordinal();
    }

    /** Tier for a provenance match type (the {@code matchType} strings the matchers emit). */
    public static EvidenceTier ofMatchType(String matchType) {
        if (matchType == null) return NONE;
        switch (matchType) {
            case "CURATED_EXACT":
                return CURATED_FULL;
            case "OLS_TEXT_TAGGER":
            case "OLS_LEXICAL_FUZZY_LABEL":
                return LABEL_FULL;
            case "OLS_TEXT_TAGGER_SYNONYM":
            case "OLS_LEXICAL_FUZZY_SYNONYM":
                return SYNONYM_FULL;
            case "CURATED_SUBSTRING":
            case "OLS_TEXT_TAGGER_SUBSTRING":
            case "OLS_LEXICAL_FUZZY":
                return PARTIAL;
            case "OLS_EMBEDDING":
                return EMBEDDING;
            default:
                return NONE;
        }
    }

    /**
     * Tier of a result, judged by its first provenance step: later steps
     * (obsolete replacement, LLM-similar expansion, OxO hop) refine <em>where</em>
     * the mapping landed, but the strength of the evidence is that of the
     * original match. Unknown match types fall back to the step's method so a
     * curated step whose text coverage is complete still counts as curated-full.
     */
    public static EvidenceTier of(List<V3MappingProvenanceStepDto> provenance) {
        if (provenance == null || provenance.isEmpty()) return NONE;
        return of(provenance.get(0));
    }

    public static EvidenceTier of(V3MappingProvenanceStepDto step) {
        if (step == null) return NONE;
        EvidenceTier byType = ofMatchType(step.matchType);
        if (byType != NONE) return byType;
        if (step.method == null) return NONE;
        switch (step.method) {
            case "curated":
                return isComplete(step) ? CURATED_FULL : PARTIAL;
            case "lexical":
                return isComplete(step) ? LABEL_FULL : PARTIAL;
            case "semantic":
                return EMBEDDING;
            default:
                return NONE;
        }
    }

    /** True if a step's text similarity (or, failing that, its confidence) says the whole query matched. */
    private static boolean isComplete(V3MappingProvenanceStepDto step) {
        Double score = step.similarity != null ? step.similarity : step.confidence;
        return score != null && score >= 1.0;
    }

    /** True if the whole query text matched a curated mapping, label or synonym. */
    public static boolean isFullMatch(List<V3MappingProvenanceStepDto> provenance) {
        return of(provenance).isFullMatch();
    }

    /**
     * True if the result rests on the query text itself matching something
     * (curated or lexical full match) rather than on an embedding guess or a
     * partial hit. This is the gate for rules like the organism/taxonomy
     * preference that must only boost matches a curator would recognise.
     */
    public static boolean isLexicallyGrounded(List<V3MappingProvenanceStepDto> provenance) {
        return isFullMatch(provenance);
    }

    /**
     * Rank of a result's evidence for tie-breaking (lower is better): the tier
     * first, then within a tier the text tagger's report ahead of the fuzzy
     * endpoint's. Both describe the same evidence (the whole query equals a
     * label), so when they tie on score the tagger, which is the primary exact
     * matcher, keeps winning as it always has and attribution stays stable.
     */
    public static int rankOf(List<V3MappingProvenanceStepDto> provenance) {
        int tierRank = of(provenance).rank();
        boolean viaFuzzyEndpoint = provenance != null && !provenance.isEmpty()
            && provenance.get(0) != null && provenance.get(0).matchType != null
            && provenance.get(0).matchType.startsWith("OLS_LEXICAL");
        return tierRank * 2 + (viaFuzzyEndpoint ? 1 : 0);
    }

    /** Highest confidence first; equal confidences ordered by {@link #rankOf} so curated evidence ranks first. */
    public static Comparator<MapResult> resultRanking() {
        return Comparator.comparingDouble((MapResult r) -> r.mappingConfidence).reversed()
            .thenComparingInt(r -> rankOf(r.mappingProvenance));
    }

    /** Highest confidence first; equal confidences ordered by {@link #rankOf} so curated evidence ranks first. */
    public static Comparator<V3MappingCandidateDto> candidateRanking() {
        return Comparator.comparingDouble((V3MappingCandidateDto c) -> c.confidence != null ? c.confidence : 0.0).reversed()
            .thenComparingInt(c -> rankOf(c.mappingProvenance));
    }
}
