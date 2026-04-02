package uk.ac.ebi.zooma2.nlp;

import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Uses Apache OpenNLP to extract noun phrase chunks from free text.
 * Pipeline: sentence detection -> tokenization -> POS tagging -> chunking -> filter NP chunks.
 * Models are loaded once at construction and are thread-safe for concurrent use.
 */
public class TextSegmenter {

    private final SentenceModel sentenceModel;
    private final TokenizerModel tokenizerModel;
    private final POSModel posModel;
    private final ChunkerModel chunkerModel;

    public TextSegmenter() throws IOException {
        this.sentenceModel = loadModel("/opennlp/en-sent.bin", SentenceModel::new);
        this.tokenizerModel = loadModel("/opennlp/en-token.bin", TokenizerModel::new);
        this.posModel = loadModel("/opennlp/en-pos.bin", POSModel::new);
        this.chunkerModel = loadModel("/opennlp/en-chunker.bin", ChunkerModel::new);
    }

    private <T> T loadModel(String resourcePath, ModelLoader<T> loader) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("OpenNLP model not found on classpath: " + resourcePath);
            }
            return loader.load(is);
        }
    }

    @FunctionalInterface
    private interface ModelLoader<T> {
        T load(InputStream is) throws IOException;
    }

    /**
     * A segment of text extracted from the input, with its character offsets in the original text.
     */
    public record TextSegment(String text, int start, int end) {}

    /**
     * Result of segmentation: all segments (with offsets) and deduplicated unique texts for mapping.
     */
    public record SegmentationResult(
            List<TextSegment> segments,
            List<String> uniqueTexts
    ) {}

    /**
     * Extract noun phrase chunks from the given text.
     * Returns segments with character offsets into the original text, plus deduplicated unique texts.
     */
    public SegmentationResult segment(String text) {
        if (text == null || text.isBlank()) {
            return new SegmentationResult(List.of(), List.of());
        }

        // Thread-local instances (ME classes are not thread-safe, but models are)
        var sentenceDetector = new SentenceDetectorME(sentenceModel);
        var tokenizer = new TokenizerME(tokenizerModel);
        var posTagger = new POSTaggerME(posModel);
        var chunker = new ChunkerME(chunkerModel);

        List<TextSegment> allSegments = new ArrayList<>();

        // Step 0: Split on blank lines (paragraph boundaries) before sentence detection.
        // OpenNLP's sentence detector may merge lines without terminal punctuation.
        var paragraphPattern = java.util.regex.Pattern.compile("\\n\\s*\\n");
        var matcher = paragraphPattern.matcher(text);
        List<int[]> paragraphRanges = new ArrayList<>();
        int pStart = 0;
        while (matcher.find()) {
            if (pStart < matcher.start()) {
                paragraphRanges.add(new int[]{pStart, matcher.start()});
            }
            pStart = matcher.end();
        }
        if (pStart < text.length()) {
            paragraphRanges.add(new int[]{pStart, text.length()});
        }

        for (int[] pRange : paragraphRanges) {
            String paragraph = text.substring(pRange[0], pRange[1]);
            int paragraphOffset = pRange[0];

        // Step 1: Detect sentences with their positions in the paragraph
        Span[] sentenceSpans = sentenceDetector.sentPosDetect(paragraph);

        for (Span sentSpan : sentenceSpans) {
            String sentence = paragraph.substring(sentSpan.getStart(), sentSpan.getEnd());
            int sentenceOffset = paragraphOffset + sentSpan.getStart();

            // Step 2: Tokenize the sentence (with positions relative to the sentence)
            Span[] tokenSpans = tokenizer.tokenizePos(sentence);
            String[] tokens = new String[tokenSpans.length];
            for (int i = 0; i < tokenSpans.length; i++) {
                tokens[i] = sentence.substring(tokenSpans[i].getStart(), tokenSpans[i].getEnd());
            }

            if (tokens.length == 0) continue;

            // Step 3: POS tag
            String[] tags = posTagger.tag(tokens);

            // Step 4: Chunk
            Span[] chunkSpans = chunker.chunkAsSpans(tokens, tags);

            // Step 5: Extract NP chunks, clean up with POS tags, compute character offsets
            for (Span chunk : chunkSpans) {
                if (!"NP".equals(chunk.getType())) continue;

                int chunkStart = chunk.getStart();
                int chunkEnd = chunk.getEnd(); // exclusive

                if (chunkStart >= chunkEnd || chunkStart >= tokenSpans.length || chunkEnd > tokenSpans.length) {
                    continue;
                }

                // 5a: Extract bracketed content as separate sub-chunks
                List<int[]> subChunks = extractBracketedContent(chunkStart, chunkEnd, tags, tokens, tokenSpans, sentenceOffset, text, allSegments);

                // 5b: Split each sub-chunk on CC and comma tokens
                List<int[]> splitChunks = new ArrayList<>();
                for (int[] sc : subChunks) {
                    splitChunks.addAll(splitOnCCAndComma(sc[0], sc[1], tags));
                }

                // 5c-d: Strip leading DT/PRP$/PDT, trailing brackets, filter non-content chunks, emit
                for (int[] sc : splitChunks) {
                    emitCleanedSegment(sc[0], sc[1], tags, tokens, tokenSpans, sentenceOffset, text, allSegments);
                }
            }
        }
        } // end paragraph loop

        // Deduplicate: case-insensitive, preserving first occurrence's casing
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> uniqueTexts = new ArrayList<>();
        for (TextSegment seg : allSegments) {
            String lower = seg.text().toLowerCase(Locale.ROOT);
            if (seen.add(lower)) {
                uniqueTexts.add(seg.text());
            }
        }

        return new SegmentationResult(allSegments, uniqueTexts);
    }

    /**
     * Extract bracketed content as separate segments, returning the remaining (non-bracket) token ranges.
     * Handles both standalone bracket tokens (-LRB-/-RRB-) and brackets glued to adjacent words
     * by the tokenizer (e.g. "(azoxystrobin" as a single token).
     */
    private List<int[]> extractBracketedContent(
            int chunkStart, int chunkEnd, String[] tags, String[] tokens, Span[] tokenSpans,
            int sentenceOffset, String text, List<TextSegment> segments) {

        List<int[]> outerRanges = new ArrayList<>();
        int i = chunkStart;
        int outerStart = chunkStart;

        while (i < chunkEnd) {
            boolean isStandaloneBracket = "-LRB-".equals(tags[i]) || "(".equals(tokens[i]) || "[".equals(tokens[i]);
            boolean hasGluedBracket = !isStandaloneBracket && tokens[i].length() > 1
                    && (tokens[i].startsWith("(") || tokens[i].startsWith("["));

            if (isStandaloneBracket || hasGluedBracket) {
                // Emit tokens before the bracket as an outer range
                if (outerStart < i) {
                    outerRanges.add(new int[]{outerStart, i});
                }
                // Find matching close bracket
                int j = i + 1;
                while (j < chunkEnd && !isCloseBracket(tags[j], tokens[j])) {
                    j++;
                }
                if (isStandaloneBracket) {
                    // Bracket is its own token — content is tokens between ( and )
                    if (i + 1 < j) {
                        emitCleanedSegment(i + 1, j, tags, tokens, tokenSpans, sentenceOffset, text, segments);
                    }
                } else {
                    // Bracket is glued to word (e.g. "(azoxystrobin") — emit with adjusted offset
                    int lastContent = (j < chunkEnd ? j : chunkEnd) - 1;
                    int adjustedCharStart = sentenceOffset + tokenSpans[i].getStart() + 1; // skip '(' or '['
                    int charEnd = sentenceOffset + tokenSpans[lastContent].getEnd();
                    // Strip trailing bracket char if glued to last token
                    if (tokens[lastContent].endsWith(")") || tokens[lastContent].endsWith("]")) {
                        charEnd--;
                    }
                    if (adjustedCharStart < charEnd) {
                        String bracketText = text.substring(adjustedCharStart, charEnd).trim();
                        if (!bracketText.isEmpty()) {
                            int ts = adjustedCharStart;
                            while (ts < charEnd && Character.isWhitespace(text.charAt(ts))) ts++;
                            int te = charEnd;
                            while (te > ts && Character.isWhitespace(text.charAt(te - 1))) te--;
                            segments.add(new TextSegment(bracketText, ts, te));
                        }
                    }
                }
                // Skip past the close bracket token (or end of chunk if unmatched)
                outerStart = (j < chunkEnd) ? j + 1 : j;
                i = outerStart;
            } else {
                i++;
            }
        }
        // Remaining tokens after last bracket
        if (outerStart < chunkEnd) {
            outerRanges.add(new int[]{outerStart, chunkEnd});
        }
        return outerRanges;
    }

    private static boolean isCloseBracket(String tag, String token) {
        return "-RRB-".equals(tag) || ")".equals(token) || "]".equals(token);
    }

    /**
     * Split a token range at CC (conjunctions) and comma tokens into sub-ranges.
     */
    private static List<int[]> splitOnCCAndComma(int start, int end, String[] tags) {
        List<int[]> parts = new ArrayList<>();
        int partStart = start;
        for (int i = start; i < end; i++) {
            if ("CC".equals(tags[i]) || ",".equals(tags[i])) {
                if (partStart < i) {
                    parts.add(new int[]{partStart, i});
                }
                partStart = i + 1;
            }
        }
        if (partStart < end) {
            parts.add(new int[]{partStart, end});
        }
        return parts;
    }

    /**
     * Strip leading DT/PRP$/PDT tokens and trailing bracket tokens, skip if no content
     * tokens remain, then emit as a TextSegment.
     * Content tokens: tags starting with NN, JJ, VBG, VBN, or CD.
     */
    private static void emitCleanedSegment(
            int start, int end, String[] tags, String[] tokens, Span[] tokenSpans,
            int sentenceOffset, String text, List<TextSegment> segments) {

        // Strip leading determiners/possessives/predeterminers
        int first = start;
        while (first < end) {
            String tag = tags[first];
            if ("DT".equals(tag) || "PRP$".equals(tag) || "PDT".equals(tag)) {
                first++;
            } else {
                break;
            }
        }
        if (first >= end) return;

        // Strip trailing bracket tokens
        int last = end - 1;
        while (last >= first && isCloseBracket(tags[last], tokens[last])) {
            last--;
        }
        if (last < first) return;

        // Check for at least one content token
        boolean hasContent = false;
        for (int i = first; i <= last; i++) {
            if (isContentTag(tags[i])) {
                hasContent = true;
                break;
            }
        }
        if (!hasContent) return;

        int charStart = sentenceOffset + tokenSpans[first].getStart();
        int charEnd = sentenceOffset + tokenSpans[last].getEnd();

        String chunkText = text.substring(charStart, charEnd).trim();
        if (chunkText.isEmpty()) return;

        // Adjust offsets for any trimmed whitespace
        int trimStart = charStart;
        while (trimStart < charEnd && Character.isWhitespace(text.charAt(trimStart))) {
            trimStart++;
        }
        int trimEnd = charEnd;
        while (trimEnd > trimStart && Character.isWhitespace(text.charAt(trimEnd - 1))) {
            trimEnd--;
        }

        segments.add(new TextSegment(chunkText, trimStart, trimEnd));
    }

    private static boolean isContentTag(String tag) {
        return tag.startsWith("NN") || tag.startsWith("JJ") || "VBG".equals(tag) || "VBN".equals(tag) || "CD".equals(tag);
    }

    /**
     * Merges NLP segments with tag_text segments.
     * NLP segments are the base. Tag_text segments are added if:
     * - They don't overlap any NLP segment (tag_text found something NLP missed), or
     * - They are strictly longer than every NLP segment they overlap (tag_text found a
     *   more specific match), in which case the shorter NLP segments are replaced.
     * If a tag_text segment overlaps an NLP segment of equal or greater length, it is discarded.
     */
    public static SegmentationResult mergeSegments(List<TextSegment> nlpSegments, List<TextSegment> tagTextSegments) {
        List<TextSegment> merged = new ArrayList<>(nlpSegments);

        for (TextSegment tagSeg : tagTextSegments) {
            int tagLen = tagSeg.end() - tagSeg.start();

            // Find all current segments this tag_text segment overlaps with
            List<TextSegment> overlapping = merged.stream()
                .filter(s -> s.start() < tagSeg.end() && s.end() > tagSeg.start())
                .toList();

            if (overlapping.isEmpty()) {
                // No overlap: tag_text found something NLP missed
                merged.add(tagSeg);
            } else {
                // Only replace if tag_text segment is strictly longer than every overlapping segment
                boolean longerThanAll = overlapping.stream()
                    .allMatch(s -> tagLen > (s.end() - s.start()));
                if (longerThanAll) {
                    merged.removeAll(overlapping);
                    merged.add(tagSeg);
                }
            }
        }

        // Sort by start position
        merged.sort(Comparator.comparingInt(TextSegment::start));

        // Deduplicate unique texts (case-insensitive)
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> uniqueTexts = new ArrayList<>();
        for (TextSegment seg : merged) {
            String lower = seg.text().toLowerCase(Locale.ROOT);
            if (seen.add(lower)) {
                uniqueTexts.add(seg.text());
            }
        }

        return new SegmentationResult(merged, uniqueTexts);
    }
}
