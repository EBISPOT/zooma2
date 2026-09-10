package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The spellings the exact-match tier tolerates (issue #21, query normalisation variants). */
class QueryVariantsTest {

    @Test
    void pluralAndSpellingVariants() {
        var v = QueryVariants.variants("tumours");
        assertTrue(v.contains("tumour"), v.toString());
        assertTrue(v.contains("tumors"), v.toString());
        assertTrue(QueryVariants.variants("categories").contains("category"));
        assertTrue(QueryVariants.variants("liver tissue").contains("liver tissues"));
        assertTrue(QueryVariants.variants("haemoglobin").contains("hemoglobin"));
        assertTrue(QueryVariants.variants("hyperglycemia").contains("hyperglycaemia"));
        assertTrue(QueryVariants.variants("leukaemia").contains("leukemia"));
    }

    @Test
    void hyphenationGreekLettersAndSymbols() {
        var beta = QueryVariants.variants("beta catenin");
        assertTrue(beta.contains("beta-catenin"), beta.toString());
        assertTrue(beta.contains("β catenin"), beta.toString());
        assertTrue(QueryVariants.variants("β-catenin").contains("beta-catenin"));
        assertTrue(QueryVariants.variants("T-cell").contains("T cell"));
        assertTrue(QueryVariants.variants("CD4 T cells").contains("CD4 T-cells"));
        assertTrue(QueryVariants.variants("age related macular degeneration").contains("age-related macular degeneration"));
        // "non-small cell", as EFO writes it, not "non-small-cell"
        assertTrue(QueryVariants.variants("non small cell lung carcinoma").contains("non-small cell lung carcinoma"));
        assertTrue(QueryVariants.variants("IL-6").contains("IL6"));
        assertTrue(QueryVariants.variants("IL6").contains("IL-6"));
    }

    @Test
    void numeralsAndPunctuation() {
        assertTrue(QueryVariants.variants("Type II diabetes").contains("Type 2 diabetes"));
        assertTrue(QueryVariants.variants("type 2 diabetes mellitus").contains("type II diabetes mellitus"));
        assertTrue(QueryVariants.variants("Grade III glioma").contains("Grade 3 glioma"));
        assertTrue(QueryVariants.variants("\"asthma\"").contains("asthma"));
        assertTrue(QueryVariants.variants("lung, upper lobe").contains("lung upper lobe"));
    }

    @Test
    void irregularPluralsAndInvariantWords() {
        assertEquals(List.of("mouse"), QueryVariants.variants("mice"));
        assertTrue(QueryVariants.variants("Mouse").contains("Mice"));
        assertTrue(QueryVariants.variants("bacteria").contains("bacterium"));
        assertTrue(QueryVariants.variants("viruses").contains("virus"));
        assertTrue(QueryVariants.variants("analysis").contains("analyses"));
        // Words that only look plural, Latin binomials and short words produce no non-words
        for (String invariant : List.of("diabetes", "species", "Homo sapiens", "Mus musculus", "E. coli", "S. cerevisiae", "rat", "box", "2 mg/kg")) {
            assertEquals(List.of(), QueryVariants.variants(invariant), invariant);
        }
    }

    @Test
    void spellingRulesAreAnchoredToAvoidUnrelatedWords() {
        assertFalse(QueryVariants.variants("chemical").contains("chaemical"));
        assertFalse(QueryVariants.variants("colorectal cancer").contains("colourectal cancer"));
        assertFalse(QueryVariants.variants("literature").contains("litreature"));
    }

    @Test
    void neverTheTextItselfCappedAndDeterministic() {
        for (String text : List.of("tumours", "beta catenin", "  liver   tissue ", "Type II diabetes", "bone marrow-derived macrophage", "IL-6")) {
            var v = QueryVariants.variants(text);
            String normalised = text.trim().replaceAll("\\s+", " ");
            assertFalse(v.contains(normalised), text);
            assertFalse(v.contains(text), text);
            assertTrue(v.size() <= QueryVariants.MAX_VARIANTS, text);
            assertEquals(v, QueryVariants.variants(text), "deterministic");
            assertEquals(v.size(), v.stream().distinct().count(), "distinct");
        }
        assertEquals(List.of(), QueryVariants.variants(null));
        assertEquals(List.of(), QueryVariants.variants("   "));
    }
}
