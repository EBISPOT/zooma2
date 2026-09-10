package uk.ac.ebi.zooma2.util;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Text similarity for fuzzy matching. Two complementary views are blended:
 * token overlap (Jaccard), which rewards multi-word queries sharing words with
 * a label, and normalised Levenshtein distance, which rewards near-identical
 * spellings that share no whole token ("melanomma" / "melanoma") and which
 * Jaccard alone scores zero. The tokenizer is Unicode-aware so Greek letters
 * and accented characters are tokens, not separators.
 *
 * <p>A third, asymmetric view, {@link #containment}, scores a query that is a
 * strict subset of a label's tokens on how much of the <em>query</em> matched:
 * "cadmium" is all of itself inside "exposure to cadmium" (issue #26).
 */
public final class StringSimilarity {

    private static final Pattern NON_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Function words that carry no content for containment: "exposure to cadmium" is about exposure and cadmium. */
    static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "of", "to", "in", "on", "by", "via", "and", "or", "with", "for", "from", "at", "as", "is", "into", "during");

    /**
     * What a containing string scores as it grows without bound relative to the
     * tightest one; see {@link #containmentScore}. Half the credit is for containing
     * the whole query at all, the other half for adding nothing to it, so that
     * specialisations of an exact match spread across the middle of the scale
     * ("cadmium chloride" is 0.75 of "cadmium") instead of crowding the top.
     */
    public static final double CONTAINMENT_FLOOR = 0.5;

    private StringSimilarity() {
    }

    /** Lower-cased alphanumeric tokens (Unicode letters and digits). */
    public static Set<String> tokens(String s) {
        if (s == null) return Set.of();
        return Arrays.stream(NON_LETTER_OR_DIGIT.split(s.toLowerCase(Locale.ROOT)))
            .filter(t -> !t.isEmpty())
            .collect(Collectors.toSet());
    }

    /** Jaccard overlap of the token sets, 0..1. */
    public static double tokenJaccard(String a, String b) {
        Set<String> tokA = tokens(a);
        Set<String> tokB = tokens(b);
        if (tokA.isEmpty() && tokB.isEmpty()) return 1.0;
        if (tokA.isEmpty() || tokB.isEmpty()) return 0.0;
        long intersection = tokA.stream().filter(tokB::contains).count();
        long union = tokA.size() + tokB.size() - intersection;
        return (double) intersection / union;
    }

    /** 1 minus the Levenshtein distance over the longer length, on lower-cased, whitespace-normalised text; 0..1. */
    public static double levenshteinSimilarity(String a, String b) {
        String x = normalise(a);
        String y = normalise(b);
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        int max = Math.max(x.length(), y.length());
        return 1.0 - (double) levenshtein(x, y) / max;
    }

    /** {@link #tokens} minus {@link #STOPWORDS}. */
    public static Set<String> contentTokens(String s) {
        return tokens(s).stream().filter(t -> !STOPWORDS.contains(t)).collect(Collectors.toSet());
    }

    /**
     * Asymmetric containment: how well {@code candidate} contains {@code query}.
     *
     * <p>0 unless every content token of the query occurs in the candidate (so a
     * long query never matches a short label, and a query of function words
     * matches nothing). Otherwise the whole query matched, and the score is
     * {@link #containmentScore} relative to the query itself: 1 when the two have
     * the same content tokens ("cadmium exposure" and "exposure to cadmium"),
     * falling towards {@link #CONTAINMENT_FLOOR} as the candidate carries more
     * tokens the query did not ask for, so the shortest label containing the
     * query ranks first: "cadmium" scores 0.75 against "exposure to cadmium" and
     * 0.667 against "exposure to cadmium via ingestion". Jaccard would give a
     * third and a quarter, which is why bare mentions used to miss the terms
     * whose labels embed them.
     */
    public static double containment(String query, String candidate) {
        Set<String> q = contentTokens(query);
        Set<String> c = contentTokens(candidate);
        if (q.isEmpty() || c.isEmpty() || !c.containsAll(q)) return 0.0;
        return containmentScore(q.size(), c.size());
    }

    /**
     * The score of a containing string with {@code candidateTokens} content
     * tokens when the tightest containing string to hand has {@code minTokens}:
     * 1 for the tightest itself, falling towards {@link #CONTAINMENT_FLOOR} for
     * longer ones, which are its specialisations. Callers that have retrieved
     * several containing labels pass the fewest tokens among them, so that
     * "exposure to cadmium" is the ontology's own term for "cadmium" when there
     * is no plain "cadmium", and a specialisation of it when there is.
     */
    public static double containmentScore(int minTokens, int candidateTokens) {
        if (candidateTokens <= minTokens) return 1.0;
        return CONTAINMENT_FLOOR + (1 - CONTAINMENT_FLOOR) * ((double) minTokens / candidateTokens);
    }

    /** The larger of {@link #tokenJaccard} and {@link #levenshteinSimilarity}. */
    public static double blended(String a, String b) {
        return Math.max(tokenJaccard(a, b), levenshteinSimilarity(a, b));
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String normalise(String s) {
        if (s == null) return "";
        return WHITESPACE.matcher(s.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
