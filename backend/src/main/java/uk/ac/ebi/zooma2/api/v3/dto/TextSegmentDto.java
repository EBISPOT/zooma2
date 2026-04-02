package uk.ac.ebi.zooma2.api.v3.dto;

/**
 * A text segment extracted by NLP, with character offsets into the original text.
 */
public class TextSegmentDto {

    public String text;
    public int start;
    public int end;

    public TextSegmentDto() {}

    public TextSegmentDto(String text, int start, int end) {
        this.text = text;
        this.start = start;
        this.end = end;
    }
}
