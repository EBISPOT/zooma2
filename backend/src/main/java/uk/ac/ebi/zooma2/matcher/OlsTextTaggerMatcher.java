package uk.ac.ebi.zooma2.matcher;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.util.TermNamespace;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matcher backed by the OLS text-tagger bulk lookup.
 *
 * <p>The OLS text tagger finds exact and substring matches in a single HTTP call
 * per batch (via {@link #bulkTagText}), making it the cheapest matcher to run.
 * Implements {@link AnnotationMatcher} so it can also be used as a single-string
 * matcher by {@link uk.ac.ebi.zooma2.search.AnnotationEngine}.
 */
public class OlsTextTaggerMatcher implements AnnotationMatcher {

    private final OlsClientRepo olsRepo;
    private final PrefixMap prefixMap;

    public OlsTextTaggerMatcher(OlsClientRepo olsRepo, PrefixMap prefixMap) {
        this.olsRepo = olsRepo;
        this.prefixMap = prefixMap;
    }

    @Override
    public String getName() {
        return "ols_text_tagger";
    }

    /** Single-string entry point satisfying {@link AnnotationMatcher}. */
    @Override
    public List<Annotation> findMatches(MatchContext context) {
        return bulkTagText(List.of(context.stringToMap))
               .getOrDefault(context.stringToMap, List.of());
    }

    /**
     * Bulk-tags all input terms via the OLS text tagger (single HTTP call).
     *
     * @return map from each input term to its list of {@link Annotation}s
     */
    public Map<String, List<Annotation>> bulkTagText(List<String> terms) {
        var tagResults = olsRepo.tagText(terms, null);
        Map<String, List<Annotation>> result = new HashMap<>();

        for (var entry : tagResults.entrySet()) {
            String inputTerm = entry.getKey();
            List<Annotation> annotations = new ArrayList<>();
            for (var match : entry.getValue()) {
                boolean isFullMatch = match.coverage >= 1.0;
                boolean isCuration = "CURATION".equals(match.stringType);
                boolean isSynonym = "synonym".equalsIgnoreCase(match.stringType);

                double confidence;
                String matchType;
                String provenanceMethod;
                String sourceType;
                String sourceName;

                if (isCuration && isFullMatch) {
                    confidence = 0.95;
                    matchType = "CURATED_EXACT";
                    provenanceMethod = "curated";
                    sourceType = "DATABASE";
                    sourceName = match.source != null ? match.source : match.ontologyId;
                } else if (isFullMatch && isSynonym) {
                    confidence = 0.9;
                    matchType = "OLS_TEXT_TAGGER_SYNONYM";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                } else if (isFullMatch) {
                    confidence = 1.0;
                    matchType = "OLS_TEXT_TAGGER";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                } else if (isCuration) {
                    confidence = match.coverage * 0.89;
                    matchType = "CURATED_SUBSTRING";
                    provenanceMethod = "curated";
                    sourceType = "DATABASE";
                    sourceName = match.source != null ? match.source : match.ontologyId;
                } else {
                    confidence = match.coverage * 0.89;
                    matchType = "OLS_TEXT_TAGGER_SUBSTRING";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                }

                Annotation a = new Annotation();
                a.annotatedProperty = new Annotation.AnnotatedProperty();
                a.annotatedProperty.propertyType = "unspecified";
                a.annotatedProperty.propertyValue = inputTerm;
                a.semanticTags = List.of(match.termIri);
                a.confidence = confidence;
                a.provenance = new Annotation.Provenance();
                a.provenance.source = new Annotation.Source();
                a.provenance.source.type = sourceType;
                a.provenance.source.name = sourceName;
                a.provenance.source.uri = sourceName;
                a.provenance.evidence = matchType;
                a.provenance.generator = "ZOOMA";
                a.provenance.generatedDate = new Date().toString();

                if ("curated".equals(provenanceMethod)) {
                    a.mappingProvenance = List.of(V3MappingProvenanceStepDto.curated(
                        sourceName, matchType, inputTerm,
                        match.termLabel, match.termIri, match.coverage
                    ));
                } else {
                    a.mappingProvenance = List.of(V3MappingProvenanceStepDto.lexical(
                        "ols:" + match.ontologyId, matchType, inputTerm,
                        match.termLabel, match.termIri, match.coverage
                    ));
                }

                // Build pre-resolved OlsTerm from tag_text metadata so we can skip resolveTerms()
                OlsTerm preResolved = new OlsTerm();
                preResolved.iri = match.termIri;
                preResolved.label = match.termLabel;
                preResolved.ontology_name = match.ontologyId;
                preResolved.short_form = match.shortForm != null ? match.shortForm : shortFormFromIri(match.termIri);
                preResolved.synonyms = match.synonyms;
                preResolved.is_obsolete = match.isObsolete;
                a.resolvedTerm = preResolved;

                annotations.add(a);
            }
            result.put(inputTerm, annotations);
        }
        return result;
    }

    /**
     * OLS-style short form for a term IRI. For OBO PURLs the IRI tail is the short
     * form OLS itself reports (e.g. NCBITaxon_10116), so use it directly — the
     * prefix-map rendering lowercases the prefix, which stops results for the same
     * term found via other matchers from deduplicating.
     */
    private String shortFormFromIri(String iri) {
        if (iri == null) return null;
        String oboPrefix = "http://purl.obolibrary.org/obo/";
        if (iri.startsWith(oboPrefix) && !iri.substring(oboPrefix.length()).contains("/")) {
            return iri.substring(oboPrefix.length());
        }
        return prefixMap.iriToShortForm(iri);
    }

    /**
     * Returns {@code true} if the tagger produced a confidence-1.0 match that satisfies
     * the target-ontology constraint. Used to short-circuit expensive matchers.
     *
     * <p>If {@code filter} specifies target ontologies, at least one 1.0-confidence annotation
     * must originate from one of them. Under {@code definingOnly} the matched term must also
     * be in a target ontology's own namespace — a full match on a term the ontology merely
     * imports will be filtered from the results, so it must not short-circuit the search
     * that could find a defining-namespace term. If no targets are set, any 1.0 match
     * qualifies.
     */
    public boolean hasFullMatchFromTargetOntologies(List<Annotation> taggerAnnotations, Filter filter) {
        if (taggerAnnotations == null || taggerAnnotations.isEmpty()) return false;
        boolean hasTargets = filter != null && filter.targetOntologies != null && !filter.targetOntologies.isEmpty();
        if (hasTargets) {
            Set<String> targets = filter.targetOntologies.stream()
                .map(String::toLowerCase).collect(Collectors.toSet());
            return taggerAnnotations.stream().anyMatch(a ->
                a.confidence >= 1.0
                && a.provenance != null && a.provenance.source != null && a.provenance.source.name != null
                && targets.contains(a.provenance.source.name.toLowerCase())
                && (!filter.definingOnly || TermNamespace.inNamespaces(firstSemanticTag(a), targets))
            );
        }
        return taggerAnnotations.stream().anyMatch(a -> a.confidence >= 1.0);
    }

    private static String firstSemanticTag(Annotation a) {
        return a.semanticTags != null && !a.semanticTags.isEmpty() ? a.semanticTags.get(0) : null;
    }
}
