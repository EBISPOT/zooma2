package uk.ac.ebi.zooma2.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import uk.ac.ebi.zooma2.model.OlsTerm;
import uk.ac.ebi.zooma2.prefix_map.PrefixMap;
import uk.ac.ebi.zooma2.repo.OlsClientRepo;

/**
 * Obsolete terms must be followed to a live replacement even when the pointer
 * is a bare short form, when the replacement was itself obsoleted later, or
 * when the term came pre-resolved from a search hit without its pointer.
 */
class ObsoleteReplacementChainTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";

    /** Shared: the constructor fetches the Bioregistry over the network. */
    private static PrefixMap prefixMap;

    @BeforeAll
    static void loadPrefixMap() {
        prefixMap = new PrefixMap();
    }

    /** Fixed OLS: each resolveTerms call is recorded so batching can be asserted. */
    private static class StubOlsRepo extends OlsClientRepo {
        final Map<String, OlsTerm> terms = new HashMap<>();
        final List<Collection<String>> calls = new ArrayList<>();

        StubOlsRepo with(OlsTerm t) { terms.put(t.iri, t); return this; }

        @Override
        public Map<String, OlsTerm> resolveTerms(Collection<String> termIris) {
            calls.add(new ArrayList<>(termIris));
            Map<String, OlsTerm> out = new HashMap<>();
            for (String iri : termIris) if (terms.containsKey(iri)) out.put(iri, terms.get(iri));
            return out;
        }
    }

    private static OlsTerm term(String shortForm, String label, Boolean obsolete, String replacedBy) {
        OlsTerm t = new OlsTerm();
        t.iri = OBO + shortForm;
        t.short_form = shortForm;
        t.label = label;
        t.ontology_name = "go";
        t.is_obsolete = obsolete;
        t.term_replaced_by = replacedBy;
        t.annotation = new HashMap<>();
        return t;
    }

    private static Map<String, OlsTerm> candidates(OlsTerm... ts) {
        Map<String, OlsTerm> m = new HashMap<>();
        for (OlsTerm t : ts) m.put(t.iri, t);
        return m;
    }

    @Test
    void shortFormPointerIsExpandedBeforeLookup() {
        // GO publishes term_replaced_by as a bare short form (verified live: GO_0000003 -> "GO_0022414")
        OlsTerm obsolete = term("GO_0000003", "obsolete reproduction", true, "GO_0022414");
        OlsTerm live = term("GO_0022414", "reproductive process", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(obsolete).with(live);

        var out = new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(obsolete));

        var res = out.get(obsolete.iri);
        assertEquals(live.iri, res.finalTerm.iri);
        assertEquals(1, res.steps.size());
        assertEquals("OBSOLETE_REPLACEMENT", res.steps.get(0).matchType);
        assertEquals(obsolete.iri, res.steps.get(0).input);
        assertEquals(live.iri, res.steps.get(0).target);
        assertEquals(List.of(List.of(live.iri)), repo.calls, "one batched lookup, by full IRI");
    }

    @Test
    void curieAndIriPointersAreAcceptedToo() {
        OlsTerm a = term("GO_0000001", "a", true, "GO:0000002");
        OlsTerm b = term("GO_0000002", "b", false, null);
        OlsTerm c = term("GO_0000003", "c", true, OBO + "GO_0000004");
        OlsTerm d = term("GO_0000004", "d", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(a).with(b).with(c).with(d);

        var out = new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(a, c));

        assertEquals(b.iri, out.get(a.iri).finalTerm.iri);
        assertEquals(d.iri, out.get(c.iri).finalTerm.iri);
        assertEquals(1, repo.calls.size(), "both chains advance in one call per hop");
    }

    @Test
    void chainedObsolescenceIsFollowedWithOneStepPerHop() {
        OlsTerm a = term("GO_0000001", "a", true, "GO_0000002");
        OlsTerm b = term("GO_0000002", "b", true, "GO_0000003"); // replaced, then obsoleted itself
        OlsTerm c = term("GO_0000003", "c", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(a).with(b).with(c);

        var res = new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(a)).get(a.iri);

        assertEquals(c.iri, res.finalTerm.iri);
        assertEquals(2, res.steps.size());
        assertEquals(a.iri, res.steps.get(0).input);
        assertEquals(b.iri, res.steps.get(0).target);
        assertEquals(b.iri, res.steps.get(1).input);
        assertEquals(c.iri, res.steps.get(1).target);
        assertEquals(2, repo.calls.size(), "one lookup per hop");
    }

    @Test
    void chainEndingAtObsoleteWithoutReplacementIsDropped() {
        OlsTerm a = term("GO_0000001", "a", true, "GO_0000002");
        OlsTerm b = term("GO_0000002", "b", true, null);
        StubOlsRepo repo = new StubOlsRepo().with(a).with(b);

        assertNull(new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(a)).get(a.iri));
    }

    @Test
    void unresolvablePointerIsDropped() {
        OlsTerm a = term("GO_0000001", "a", true, "GO_0000002");
        StubOlsRepo repo = new StubOlsRepo().with(a); // GO_0000002 unknown to OLS

        assertTrue(new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(a)).isEmpty());
    }

    @Test
    void cycleIsDetectedAndDropped() {
        OlsTerm a = term("GO_0000001", "a", true, "GO_0000002");
        OlsTerm b = term("GO_0000002", "b", true, "GO_0000001");
        StubOlsRepo repo = new StubOlsRepo().with(a).with(b);

        assertTrue(new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(a, b)).isEmpty());
        assertTrue(repo.calls.size() <= ObsoleteTermResolver.MAX_HOPS, "cycle must not run to the hop cap");
    }

    @Test
    void chainLongerThanCapIsDropped() {
        StubOlsRepo repo = new StubOlsRepo();
        int n = ObsoleteTermResolver.MAX_HOPS + 2;
        OlsTerm first = null;
        for (int i = 1; i <= n; i++) {
            boolean last = i == n;
            OlsTerm t = term(String.format("GO_%07d", i), "t" + i, !last, last ? null : String.format("GO_%07d", i + 1));
            repo.with(t);
            if (first == null) first = t;
        }
        assertTrue(new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(first)).isEmpty());

        // Exactly at the cap it still resolves
        StubOlsRepo repo2 = new StubOlsRepo();
        first = null;
        for (int i = 1; i <= ObsoleteTermResolver.MAX_HOPS + 1; i++) {
            boolean last = i == ObsoleteTermResolver.MAX_HOPS + 1;
            OlsTerm t = term(String.format("GO_%07d", i), "t" + i, !last, last ? null : String.format("GO_%07d", i + 1));
            repo2.with(t);
            if (first == null) first = t;
        }
        var res = new ObsoleteTermResolver(repo2, prefixMap).resolve(candidates(first)).get(first.iri);
        assertEquals(ObsoleteTermResolver.MAX_HOPS, res.steps.size());
    }

    @Test
    void preResolvedPartialTermIsReFetchedForItsPointer() {
        // What a tag_text / llm_search hit carries: obsolete flag, no pointer
        OlsTerm partial = term("GO_0000003", "obsolete reproduction", true, null);
        partial.annotation = null;
        OlsTerm full = term("GO_0000003", "obsolete reproduction", true, "GO_0022414");
        OlsTerm live = term("GO_0022414", "reproductive process", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(full).with(live);

        var res = new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(partial)).get(partial.iri);

        assertEquals(live.iri, res.finalTerm.iri);
        assertEquals(1, res.steps.size());
        assertEquals(List.of(partial.iri), repo.calls.get(0), "full record fetched first");
    }

    @Test
    void partialTermThatTurnsOutLiveResolvesToItselfWithNoSteps() {
        // A stale "obsolete" flag on a search hit: the full record says it is live
        OlsTerm partial = term("GO_0000010", "x", true, null);
        OlsTerm full = term("GO_0000010", "x", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(full);

        var res = new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(partial)).get(partial.iri);

        assertEquals(full.iri, res.finalTerm.iri);
        assertFalse(res.finalTerm.isObsolete());
        assertTrue(res.steps.isEmpty());
    }

    @Test
    void considerAnnotationIsUsedWhenTermReplacedByIsAbsent() {
        OlsTerm a = term("MONDO_0000001", "a", true, null);
        a.annotation = Map.of("consider", List.of("MONDO:0000002"));
        OlsTerm b = term("MONDO_0000002", "b", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(a).with(b);

        var res = new ObsoleteTermResolver(repo, prefixMap).resolve(candidates(a)).get(a.iri);
        assertEquals(b.iri, res.finalTerm.iri);
    }

    @Test
    void liveTermsAreIgnoredAndInputIsNotModified() {
        OlsTerm live = term("GO_0000005", "live", false, null);
        StubOlsRepo repo = new StubOlsRepo().with(live);
        Map<String, OlsTerm> in = candidates(live);

        assertTrue(new ObsoleteTermResolver(repo, prefixMap).resolve(in).isEmpty());
        assertTrue(repo.calls.isEmpty());
        assertEquals(1, in.size());
    }
}
