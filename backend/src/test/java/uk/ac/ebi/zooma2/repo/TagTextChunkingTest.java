package uk.ac.ebi.zooma2.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Bulk tag_text is split into bounded chunks, and each term keeps only matches that clear its own minimum (issue #15, points 3 and 4). */
class TagTextChunkingTest {

    @Test
    void aBatchThatFitsIsOneChunkInInputOrder() {
        List<String> terms = List.of("rat", "Big cells", "left tibia");
        assertEquals(List.of(terms), OlsClientRepo.chunkTerms(terms, OlsClientRepo.TAG_TEXT_MAX_CHUNK_BYTES, OlsClientRepo.TAG_TEXT_MAX_CHUNK_TERMS));
    }

    @Test
    void chunksAreBoundedByCountAndByBytes() {
        List<String> terms = List.of("aa", "bb", "cc", "dd", "ee");
        assertEquals(List.of(List.of("aa", "bb"), List.of("cc", "dd"), List.of("ee")), OlsClientRepo.chunkTerms(terms, 1000, 2));
        // 3 bytes per term including the newline separator: two fit in 6, the third does not
        assertEquals(List.of(List.of("aa", "bb"), List.of("cc", "dd"), List.of("ee")), OlsClientRepo.chunkTerms(terms, 6, 100));
        // an oversized term still gets its own chunk rather than being dropped
        assertEquals(List.of(List.of("aa"), List.of("a very long term"), List.of("bb")), OlsClientRepo.chunkTerms(List.of("aa", "a very long term", "bb"), 6, 100));
        // multi-byte text is measured in UTF-8 bytes
        assertEquals(2, OlsClientRepo.chunkTerms(List.of("αβγ", "δεζ"), 8, 100).size());
    }

    @Test
    void eachTermKeepsOnlyMatchesThatClearItsOwnMinimum() {
        // "rat" alone would be requested with minLength 3, so a 3-byte match counts
        assertTrue(OlsClientRepo.acceptsMatch("rat", 3));
        // "Big cells" alone would be requested with minLength 6: a 3-byte hit on "Big"
        // must not appear just because "rat" shared the batch
        assertFalse(OlsClientRepo.acceptsMatch("Big cells", 3));
        assertTrue(OlsClientRepo.acceptsMatch("Big cells", 6));
        assertTrue(OlsClientRepo.acceptsMatch("ab", 2));
        assertFalse(OlsClientRepo.acceptsMatch("ab", 1));
    }
}
