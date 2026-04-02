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

import java.io.InputStream;

public class DebugSegmenter {
    public static void main(String[] args) throws Exception {
        var sentModel = new SentenceModel(load("/opennlp/en-sent.bin"));
        var tokModel = new TokenizerModel(load("/opennlp/en-token.bin"));
        var posModel = new POSModel(load("/opennlp/en-pos.bin"));
        var chunkModel = new ChunkerModel(load("/opennlp/en-chunker.bin"));

        String text = "Multi-omics phenotyping of the gut-liver axis reveals metabolic perturbations from a low-dose pesticide mixture in rats\n\nHealth effects of pesticides are not always accurately detected using the current battery of regulatory toxicity tests.";

        var sentDetector = new SentenceDetectorME(sentModel);
        var tokenizer = new TokenizerME(tokModel);
        var posTagger = new POSTaggerME(posModel);
        var chunker = new ChunkerME(chunkModel);

        Span[] sentSpans = sentDetector.sentPosDetect(text);
        for (Span sent : sentSpans) {
            String sentence = text.substring(sent.getStart(), sent.getEnd());
            System.out.println("=== SENTENCE: " + sentence);

            Span[] tokenSpans = tokenizer.tokenizePos(sentence);
            String[] tokens = new String[tokenSpans.length];
            for (int i = 0; i < tokenSpans.length; i++) {
                tokens[i] = sentence.substring(tokenSpans[i].getStart(), tokenSpans[i].getEnd());
            }

            String[] tags = posTagger.tag(tokens);

            System.out.println("\nTOKENS + POS:");
            for (int i = 0; i < tokens.length; i++) {
                System.out.printf("  [%2d] %-25s %-8s  (chars %d-%d)%n",
                    i, tokens[i], tags[i], tokenSpans[i].getStart(), tokenSpans[i].getEnd());
            }

            Span[] chunks = chunker.chunkAsSpans(tokens, tags);
            System.out.println("\nCHUNKS:");
            for (Span c : chunks) {
                StringBuilder sb = new StringBuilder();
                for (int i = c.getStart(); i < c.getEnd(); i++) {
                    if (i > c.getStart()) sb.append(" ");
                    sb.append(tokens[i]).append("/").append(tags[i]);
                }
                int charStart = tokenSpans[c.getStart()].getStart();
                int charEnd = tokenSpans[c.getEnd() - 1].getEnd();
                String rawText = sentence.substring(charStart, charEnd);
                System.out.printf("  %s [%d-%d] tokens[%d-%d]: %s -> \"%s\"%n",
                    c.getType(), charStart, charEnd, c.getStart(), c.getEnd() - 1, sb, rawText);
            }
        }

        // Now run the full segment() pipeline and show results
        System.out.println("\n=== CLEANED SEGMENTS (from segment()) ===");
        var segmenter = new TextSegmenter();
        var result = segmenter.segment(text);
        for (var seg : result.segments()) {
            System.out.printf("  [%3d-%3d] \"%s\"%n", seg.start(), seg.end(), seg.text());
        }
        System.out.println("\nUNIQUE TEXTS: " + result.uniqueTexts());
    }

    private static InputStream load(String path) {
        return DebugSegmenter.class.getResourceAsStream(path);
    }
}
