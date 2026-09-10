package uk.ac.ebi.zooma2.matcher;

import uk.ac.ebi.zooma2.api.v3.dto.V3MappingProvenanceStepDto;
import uk.ac.ebi.zooma2.model.Annotation;
import uk.ac.ebi.zooma2.model.Filter;
import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;
import uk.ac.ebi.zooma2.Deduplicator;
import uk.ac.ebi.zooma2.util.TermIds;
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

                String matchType;
                String provenanceMethod;
                String sourceType;
                String sourceName;

                if (isCuration && isFullMatch) {
                    matchType = "CURATED_EXACT";
                    provenanceMethod = "curated";
                    sourceType = "DATABASE";
                    sourceName = match.source != null ? match.source : match.ontologyId;
                } else if (isFullMatch && isSynonym) {
                    matchType = "OLS_TEXT_TAGGER_SYNONYM";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                } else if (isFullMatch) {
                    matchType = "OLS_TEXT_TAGGER";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                } else if (isCuration) {
                    matchType = "CURATED_SUBSTRING";
                    provenanceMethod = "curated";
                    sourceType = "DATABASE";
                    sourceName = match.source != null ? match.source : match.ontologyId;
                } else {
                    matchType = "OLS_TEXT_TAGGER_SUBSTRING";
                    provenanceMethod = "lexical";
                    sourceType = "ONTOLOGY";
                    sourceName = match.ontologyId;
                }
                // The score belongs to the evidence class, not to this matcher: see EvidenceTier.
                double confidence = EvidenceTier.ofMatchType(matchType).confidence(match.coverage);

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
                preResolved.short_form = match.shortForm != null ? match.shortForm : shortFormFromIri(match.termIri, match.ontologyId);
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
     * tag_text reports only the IRI, so derive the short form the way OLS does for
     * that ontology (EFO_0000400, mesh_D000686, ORDO_224, NCBITaxon_10116), so the
     * id matches what other matchers get from OLS for the same term. Results are
     * deduplicated by IRI regardless, so this only affects the id clients see.
     */
    private String shortFormFromIri(String iri, String ontologyId) {
        if (iri == null) return null;
        String olsStyle = olsRepo.olsShortForm(ontologyId, iri);
        return olsStyle != null ? olsStyle : prefixMap.iriToShortForm(iri);
    }

    /**
     * Returns {@code true} if the tagger produced a definitive match (curated full match or
     * primary-label full match, see {@link EvidenceTier#isDefinitive()}) that satisfies the
     * target-ontology constraint. Used to short-circuit expensive matchers. The test is on
     * the evidence tier rather than {@code confidence >= 1.0} so that a curated full match
     * short-circuits just like a label match.
     *
     * <p>If {@code filter} specifies target ontologies, at least one definitive annotation
     * must originate from one of them. Under {@code definingOnly} the matched term must also
     * be in a target ontology's own namespace — a full match on a term the ontology merely
     * imports will be filtered from the results, so it must not short-circuit the search
     * that could find a defining-namespace term. If no targets are set, any definitive match
     * qualifies.
     */
    public boolean hasFullMatchFromTargetOntologies(List<Annotation> taggerAnnotations, Filter filter) {
        return hasFullMatchFromTargetOntologies(taggerAnnotations, filter, null);
    }

    /**
     * As above, ignoring annotations whose term the caller excluded ("try again"):
     * when the only definitive match is the excluded term, the search must go on
     * to find alternatives rather than short-circuit to an empty answer.
     */
    public boolean hasFullMatchFromTargetOntologies(List<Annotation> taggerAnnotations, Filter filter, List<String> excludeTermIds) {
        if (taggerAnnotations == null || taggerAnnotations.isEmpty()) return false;
        Set<String> excluded = Deduplicator.excludedForms(excludeTermIds, prefixMap);
        List<Annotation> eligible = taggerAnnotations.stream()
            .filter(a -> !TermIds.matchesAny(excluded, a.resolvedTerm != null ? a.resolvedTerm.short_form : null, firstSemanticTag(a)))
            .collect(Collectors.toList());
        boolean hasTargets = filter != null && filter.targetOntologies != null && !filter.targetOntologies.isEmpty();
        if (hasTargets) {
            Set<String> targets = filter.targetOntologies.stream()
                .map(String::toLowerCase).collect(Collectors.toSet());
            return eligible.stream().anyMatch(a ->
                isDefinitive(a)
                && a.provenance != null && a.provenance.source != null && a.provenance.source.name != null
                && targets.contains(a.provenance.source.name.toLowerCase())
                && (!filter.definingOnly || TermNamespace.inNamespaces(namespaceIdOf(a), targets))
            );
        }
        return eligible.stream().anyMatch(OlsTextTaggerMatcher::isDefinitive);
    }

    private static String firstSemanticTag(Annotation a) {
        return a.semanticTags != null && !a.semanticTags.isEmpty() ? a.semanticTags.get(0) : null;
    }

    private static boolean isDefinitive(Annotation a) {
        return EvidenceTier.of(a.mappingProvenance).isDefinitive();
    }

    /**
     * The id to judge an annotation's namespace by: the OLS short form when the
     * term is resolved (it carries the ontology's own prefix even for ontologies
     * whose IRIs don't, e.g. EDAM_data_0849), otherwise the IRI.
     */
    static String namespaceIdOf(Annotation a) {
        if (a.resolvedTerm != null && a.resolvedTerm.short_form != null && !a.resolvedTerm.short_form.isBlank()) {
            return a.resolvedTerm.short_form;
        }
        return a.semanticTags != null && !a.semanticTags.isEmpty() ? a.semanticTags.get(0) : null;
    }
}
