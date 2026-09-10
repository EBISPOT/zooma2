package uk.ac.ebi.zooma2.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, deterministic set of spellings a query might have been written in.
 *
 * <p>The exact-match tier had no tolerance at all: "tumours" fell off it onto
 * fuzzy and embedding search although "tumour" is a label, and so did
 * hyphenation, punctuation, Greek letters spelled out, British/American
 * spelling and roman numerals. Old ZOOMA's normaliser generated exactly such
 * variants. Each variant is fed to the bulk text tagger alongside the original
 * and its hits are attributed back to the original at a small discount.
 *
 * <p>Every variant is the whitespace-normalised text with exactly one rule
 * applied, never equal to the text itself, at most {@link #MAX_VARIANTS} of
 * them, in a fixed order. The rules are conservative: a variant that is not a
 * word costs a few bytes in the tagger request and can match nothing, but the
 * tables below keep the obvious non-words ("diabete", "mouses") out.
 */
public final class QueryVariants {

    public static final int MAX_VARIANTS = 8;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile("[,;:()\\[\\]\"'`]+");
    private static final Pattern ROMAN_AFTER_KEYWORD = Pattern.compile(
        "\\b((?:type|grade|stage|class|phase|group|level))\\s+(i|ii|iii|iv|v|vi|vii|viii|ix|x)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARABIC_AFTER_KEYWORD = Pattern.compile(
        "\\b((?:type|grade|stage|class|phase|group|level))\\s+([1-9]|10)\\b", Pattern.CASE_INSENSITIVE);
    private static final String[] ROMAN = {"i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};
    private static final Pattern SYMBOL_HYPHEN = Pattern.compile("^([A-Za-z]{1,6})-(\\d+[A-Za-z]?)$");
    private static final Pattern SYMBOL_JOINED = Pattern.compile("^([A-Za-z]{2,6})(\\d+[A-Za-z]?)$");

    private static final Map<String, String> GREEK = Map.ofEntries(
        Map.entry("α", "alpha"), Map.entry("β", "beta"), Map.entry("γ", "gamma"), Map.entry("δ", "delta"),
        Map.entry("ε", "epsilon"), Map.entry("κ", "kappa"), Map.entry("λ", "lambda"), Map.entry("μ", "mu"),
        Map.entry("σ", "sigma"), Map.entry("τ", "tau"), Map.entry("ω", "omega"));

    /** Words a hyphen commonly follows ("beta-catenin", "non-small", "T-cell") ... */
    private static final Set<String> HYPHEN_PREFIXES = Set.of(
        "alpha", "beta", "gamma", "delta", "epsilon", "kappa", "lambda", "sigma", "omega",
        "non", "anti", "pre", "post", "co", "self", "wild", "multi", "sub", "super", "trans", "cis",
        "inter", "intra", "extra", "ultra", "micro", "macro", "semi", "t", "b", "nk", "x", "y");
    /** ... and words it commonly precedes ("bone marrow-derived", "age-related", "T cell" is above). */
    private static final Set<String> HYPHEN_SUFFIXES = Set.of(
        "type", "like", "specific", "dependent", "independent", "induced", "mediated", "associated",
        "related", "derived", "based", "positive", "negative", "term", "free", "deficient", "resistant", "sensitive",
        "binding", "containing", "linked", "coupled", "onset", "stage", "grade", "responsive", "treated");

    /**
     * British ↔ American spelling, as regexes applied case-insensitively; the
     * first rule that matches is applied. Anchored where the fragment occurs
     * inside unrelated words ("chemical", "colorectal", "literature").
     */
    private static final String[][] SPELLING = {
        {"oesophag", "esophag"}, {"leukaemi", "leukemi"}, {"anaemi", "anemi"}, {"ischaemi", "ischemi"},
        {"diarrhoea", "diarrhea"}, {"paediatric", "pediatric"}, {"orthopaedic", "orthopedic"},
        {"gynaecolog", "gynecolog"}, {"oestrogen", "estrogen"}, {"behaviour", "behavior"}, {"tumour", "tumor"},
        {"foetal", "fetal"}, {"foetus", "fetus"}, {"oedema", "edema"}, {"\\bhaem", "\\bhem"},
        {"aemia\\b", "emia\\b"}, {"isation", "ization"}, {"yse(s|d)?\\b", "yze$1\\b"}
    };

    /** Nouns whose plural is not formed by a suffix rule, in both directions. */
    private static final Map<String, String> IRREGULAR = new HashMap<>();
    static {
        String[][] pairs = {
            {"mouse", "mice"}, {"louse", "lice"}, {"foot", "feet"}, {"tooth", "teeth"}, {"goose", "geese"},
            {"child", "children"}, {"woman", "women"}, {"man", "men"},
            {"bacterium", "bacteria"}, {"cilium", "cilia"}, {"flagellum", "flagella"}, {"medium", "media"},
            {"serum", "sera"}, {"ovum", "ova"}, {"datum", "data"}, {"stratum", "strata"},
            {"fungus", "fungi"}, {"nucleus", "nuclei"}, {"stimulus", "stimuli"}, {"bacillus", "bacilli"},
            {"radius", "radii"}, {"thrombus", "thrombi"}, {"embolus", "emboli"}, {"bronchus", "bronchi"},
            {"alveolus", "alveoli"}, {"glomerulus", "glomeruli"}, {"villus", "villi"}, {"locus", "loci"},
            {"larva", "larvae"}, {"alga", "algae"}, {"vertebra", "vertebrae"}, {"antenna", "antennae"},
            {"mitochondrion", "mitochondria"}, {"criterion", "criteria"}, {"phenomenon", "phenomena"},
            {"genus", "genera"}, {"testis", "testes"}, {"diagnosis", "diagnoses"}, {"analysis", "analyses"},
            {"hypothesis", "hypotheses"}, {"metastasis", "metastases"}, {"neurosis", "neuroses"},
            {"psychosis", "psychoses"}, {"index", "indices"}, {"matrix", "matrices"}, {"vertex", "vertices"},
            {"apex", "apices"}, {"cortex", "cortices"}, {"appendix", "appendices"}, {"virus", "viruses"},
            {"foetus", "foetuses"}, {"fetus", "fetuses"}, {"sinus", "sinuses"}, {"uterus", "uteri"}
        };
        for (String[] p : pairs) {
            IRREGULAR.put(p[0], p[1]);
            IRREGULAR.put(p[1], p[0]);
        }
    }

    /** Words that look plural or singular but are neither ("diabetes", "species"), or Latin epithets. */
    private static final Set<String> INVARIANT = Set.of(
        "diabetes", "species", "mellitus", "sapiens", "series", "herpes", "rabies", "measles", "scabies",
        "pertussis", "faeces", "feces", "mumps", "caries", "lens", "pancreas", "atlas", "biceps", "triceps",
        "forceps", "news", "coli", "elegans", "cerevisiae", "thaliana", "musculus", "norvegicus", "rerio",
        "melanogaster", "laevis", "familiaris", "taurus", "scrofa", "gallus", "aureus", "influenzae",
        "sativa", "mays", "vulgaris", "pombe", "tropicalis", "yeast", "vitro", "vivo", "situ", "silico");

    private QueryVariants() {
    }

    public static List<String> variants(String text) {
        if (text == null) return List.of();
        String base = WHITESPACE.matcher(text.trim()).replaceAll(" ");
        if (base.isEmpty()) return List.of();

        Set<String> out = new LinkedHashSet<>();
        add(out, base, base.replace('-', ' ').replace('_', ' '));
        add(out, base, hyphenate(base));
        add(out, base, joinOrSplitSymbol(base));
        add(out, base, PUNCTUATION.matcher(base).replaceAll(" ").replaceAll("\\.$", ""));
        add(out, base, pluralOrSingular(base));
        add(out, base, swapGreek(base));
        add(out, base, swapSpelling(base));
        add(out, base, swapNumerals(base));

        List<String> result = new ArrayList<>();
        for (String v : out) {
            if (result.size() >= MAX_VARIANTS) break;
            result.add(v);
        }
        return result;
    }

    private static void add(Set<String> out, String base, String candidate) {
        if (candidate == null) return;
        String v = WHITESPACE.matcher(candidate.trim()).replaceAll(" ");
        if (!v.isEmpty() && !v.equals(base)) out.add(v);
    }

    /** "beta catenin" → "beta-catenin", "bone marrow derived macrophage" → "bone marrow-derived macrophage". */
    static String hyphenate(String base) {
        String[] words = base.split(" ");
        StringBuilder sb = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            String prev = words[i - 1].toLowerCase(Locale.ROOT);
            String next = words[i].toLowerCase(Locale.ROOT);
            boolean join = (HYPHEN_PREFIXES.contains(prev) || HYPHEN_SUFFIXES.contains(next))
                && isWord(words[i - 1]) && isWord(words[i]);
            sb.append(join ? '-' : ' ').append(words[i]);
        }
        return sb.toString();
    }

    private static boolean isWord(String w) {
        return !w.isEmpty() && w.chars().allMatch(Character::isLetter);
    }

    /** "IL-6" ↔ "IL6", "HIV-1" ↔ "HIV1": gene and virus symbols are written both ways. */
    static String joinOrSplitSymbol(String base) {
        Matcher hyphen = SYMBOL_HYPHEN.matcher(base);
        if (hyphen.matches()) return hyphen.group(1) + hyphen.group(2);
        Matcher joined = SYMBOL_JOINED.matcher(base);
        if (joined.matches()) return joined.group(1) + "-" + joined.group(2);
        return base;
    }

    /** Toggles the number of the last word: tumours ↔ tumour, categories ↔ category, mice ↔ mouse. */
    static String pluralOrSingular(String base) {
        int cut = base.lastIndexOf(' ') + 1;
        String head = base.substring(0, cut);
        String word = base.substring(cut);
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.length() < 4 || !isWord(word) || INVARIANT.contains(lower)) return base;
        if (head.contains(".")) return base; // "E. coli", "S. cerevisiae": abbreviated binomials
        String irregular = IRREGULAR.get(lower);
        if (irregular != null) return head + matchCase(word, irregular);
        if (lower.endsWith("ies")) return head + word.substring(0, word.length() - 3) + "y";
        if (lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("xes") || lower.endsWith("zes")
            || lower.endsWith("sses") || lower.endsWith("uses")) {
            return head + word.substring(0, word.length() - 2);
        }
        if (lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is") || lower.endsWith("ous")) return base;
        if (lower.endsWith("s")) return head + word.substring(0, word.length() - 1);
        if (lower.endsWith("a") || lower.endsWith("um") || lower.endsWith("on")) return base; // Latin: handled above or left alone
        if (lower.endsWith("y") && !isVowel(lower.charAt(lower.length() - 2))) {
            return head + word.substring(0, word.length() - 1) + "ies";
        }
        if (lower.endsWith("ch") || lower.endsWith("sh") || lower.endsWith("x") || lower.endsWith("z")) return head + word + "es";
        return head + word + "s";
    }

    private static String matchCase(String original, String replacement) {
        if (original.isEmpty() || !Character.isUpperCase(original.charAt(0))) return replacement;
        return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }

    static String swapGreek(String base) {
        String result = base;
        for (var e : GREEK.entrySet()) {
            if (result.contains(e.getKey())) {
                result = result.replace(e.getKey(), e.getValue());
            } else {
                result = result.replaceAll("(?i)\\b" + e.getValue() + "\\b", Matcher.quoteReplacement(e.getKey()));
            }
        }
        return result;
    }

    static String swapSpelling(String base) {
        for (String[] pair : SPELLING) {
            for (int dir = 0; dir < 2; dir++) {
                Pattern from = Pattern.compile(pair[dir], Pattern.CASE_INSENSITIVE);
                Matcher m = from.matcher(base);
                if (m.find()) {
                    return m.replaceAll(pair[1 - dir].replace("\\b", ""));
                }
            }
        }
        return base;
    }

    static String swapNumerals(String base) {
        Matcher roman = ROMAN_AFTER_KEYWORD.matcher(base);
        if (roman.find()) {
            String numeral = roman.group(2).toLowerCase(Locale.ROOT);
            for (int i = 0; i < ROMAN.length; i++) {
                if (ROMAN[i].equals(numeral)) {
                    return base.substring(0, roman.start(2)) + (i + 1) + base.substring(roman.end(2));
                }
            }
        }
        Matcher arabic = ARABIC_AFTER_KEYWORD.matcher(base);
        if (arabic.find()) {
            int n = Integer.parseInt(arabic.group(2));
            return base.substring(0, arabic.start(2)) + ROMAN[n - 1].toUpperCase(Locale.ROOT) + base.substring(arabic.end(2));
        }
        return base;
    }
}
