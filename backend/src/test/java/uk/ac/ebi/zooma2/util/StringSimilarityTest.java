package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class StringSimilarityTest {

    @Test
    void tokenizerIsUnicodeAware() {
        assertEquals(Set.of("α", "tubulin"), StringSimilarity.tokens("α-tubulin"));
        assertEquals(Set.of("β", "catenin", "1"), StringSimilarity.tokens("β-Catenin 1"));
        assertEquals(Set.of("long", "qt", "syndrome", "3", "6", "digenic"), StringSimilarity.tokens("Long QT syndrome 3/6, digenic"));
        assertEquals(Set.of(), StringSimilarity.tokens(null));
    }

    @Test
    void jaccardIsWholeTokenOverlap() {
        assertEquals(0.5, StringSimilarity.tokenJaccard("cooked broccoli", "broccoli"), 1e-9);
        assertEquals(0.0, StringSimilarity.tokenJaccard("melanomma", "melanoma"), 1e-9, "no shared token");
        assertEquals(1.0, StringSimilarity.tokenJaccard("α-tubulin", "Α tubulin"), 1e-9, "case-folded Greek");
    }

    @Test
    void levenshteinSimilarityCatchesTyposAndVariants() {
        assertEquals(1 - 1.0 / 9, StringSimilarity.levenshteinSimilarity("melanomma", "melanoma"), 1e-9);
        assertEquals(1.0, StringSimilarity.levenshteinSimilarity("Rat", "rat"), 1e-9);
        assertEquals(0.0, StringSimilarity.levenshteinSimilarity("", "rat"), 1e-9);
        assertEquals(1.0, StringSimilarity.levenshteinSimilarity("", ""), 1e-9);
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting"));
    }

    @Test
    void containmentScoresQueryCoverageAsymmetrically() {
        // all of "cadmium" is inside the label; the label's extra content token costs a little
        assertEquals(0.75, StringSimilarity.containment("cadmium", "exposure to cadmium"), 1e-9);
        assertEquals(0.5 + 0.5 / 3, StringSimilarity.containment("cadmium", "exposure to cadmium via ingestion"), 1e-9);
        assertEquals(0.5 + 0.5 / 4, StringSimilarity.containment("Cadmium", "cadmium molecular entity exposure"), 1e-9);
        assertEquals(0.5 + 0.5 * 2 / 3, StringSimilarity.containment("physical activity", "exposure to Physical Activity"), 1e-9);
        // relative to the tightest containing string to hand: itself 1, longer ones less
        assertEquals(1.0, StringSimilarity.containmentScore(2, 2), 1e-9);
        assertEquals(0.5 + 0.5 * 2 / 3, StringSimilarity.containmentScore(2, 3), 1e-9);
        assertEquals(0.75, StringSimilarity.containmentScore(2, 4), 1e-9);
        // same content tokens in any order, with or without function words
        assertEquals(1.0, StringSimilarity.containment("cadmium exposure", "exposure to cadmium"), 1e-9);
        // asymmetric: a long query never matches a short label
        assertEquals(0.0, StringSimilarity.containment("exposure to cadmium", "cadmium"), 1e-9);
        // any missing query token, or a query of function words only, is no containment at all
        assertEquals(0.0, StringSimilarity.containment("cadmium", "exposure to copper"), 1e-9);
        assertEquals(0.0, StringSimilarity.containment("cadmium chloride", "exposure to cadmium"), 1e-9);
        assertEquals(0.0, StringSimilarity.containment("to", "exposure to cadmium"), 1e-9);
        assertEquals(0.0, StringSimilarity.containment("", "exposure to cadmium"), 1e-9);
        assertEquals(Set.of("exposure", "cadmium"), StringSimilarity.contentTokens("exposure to cadmium"));
    }

    @Test
    void blendedTakesTheStrongerSignal() {
        // typo: Jaccard 0, character similarity high
        assertEquals(1 - 1.0 / 9, StringSimilarity.blended("melanomma", "melanoma"), 1e-9);
        // sub-phrase: Jaccard beats characters
        assertTrue(StringSimilarity.blended("cooked broccoli", "broccoli") >= 0.5);
        assertEquals(0.8, StringSimilarity.tokenJaccard("Long QT syndrome 3/6", "long QT syndrome 3"), 1e-9);
        assertTrue(StringSimilarity.blended("Long QT syndrome 3/6", "long QT syndrome 3") >= 0.8);
    }
}
