package uk.ac.ebi.zooma2.matcher;

import java.util.Locale;
import java.util.Map;

/**
 * Curated fallback mapping common organism shorthand to NCBI Taxonomy terms.
 *
 * <p>Most common names ("rat", "mouse", "human", "zebrafish", "fission yeast")
 * are exact synonyms in NCBI Taxonomy itself and are found by the OLS text
 * tagger. Bare "yeast" is not: NCBI lists "baker's yeast" and "brewer's yeast"
 * for Saccharomyces cerevisiae but not "yeast", and none of the curated
 * datasources contain it either — Atlas curators standardised organism values
 * to full species names ("Saccharomyces cerevisiae" → NCBITaxon_4932), so the
 * bare shorthand every submitter actually writes has no curated row (verified
 * against the full zoomage_report.CURATED.tsv, metabolights, BioSamples and
 * HCA imports, and the OLS tag_text curation lexicon). This table supplies
 * exactly that missing knowledge.
 *
 * <p>Keep entries minimal: only names that are (a) standard model-organism
 * shorthand with one unambiguous referent in practice, and (b) verifiably
 * absent from NCBI Taxonomy synonyms and every curated datasource.
 */
public final class CommonOrganismNames {

    private static final Map<String, String> NAME_TO_TAXON_IRI = Map.of(
        "yeast", "http://purl.obolibrary.org/obo/NCBITaxon_4932",
        "budding yeast", "http://purl.obolibrary.org/obo/NCBITaxon_4932"
    );

    private CommonOrganismNames() {
    }

    /**
     * The NCBI Taxonomy IRI for a common organism name, or {@code null} if the
     * name is not in the curated table.
     */
    public static String taxonIri(String name) {
        if (name == null) return null;
        String normalised = name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        return NAME_TO_TAXON_IRI.get(normalised);
    }
}
