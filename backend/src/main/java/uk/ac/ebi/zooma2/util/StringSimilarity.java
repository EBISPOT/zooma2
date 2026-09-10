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
 */
public final class StringSimilarity {

    private static final Pattern NON_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
