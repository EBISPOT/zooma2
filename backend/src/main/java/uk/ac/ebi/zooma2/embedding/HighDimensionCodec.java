package uk.ac.ebi.zooma2.embedding;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;

/**
 * Custom codec that supports high-dimensional vectors (> 1024 dimensions)
 * by overriding the default dimension limit in Lucene99HnswVectorsFormat.
 */
public class HighDimensionCodec extends FilterCodec {
    
    private final KnnVectorsFormat vectorsFormat;
    
    public HighDimensionCodec() {
        super("HighDimensionCodec", new Lucene99Codec());
        this.vectorsFormat = new HighDimensionKnnVectorsFormat();
    }
    
    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return vectorsFormat;
    }
    
    /**
     * Custom KnnVectorsFormat that delegates to Lucene99HnswVectorsFormat
     * but overrides the maximum dimension limit to support vectors up to 4096 dimensions.
     */
    private static class HighDimensionKnnVectorsFormat extends KnnVectorsFormat {
        
        private final Lucene99HnswVectorsFormat delegate;
        
        public HighDimensionKnnVectorsFormat() {
            super("HighDimensionKnnVectorsFormat");
            this.delegate = new Lucene99HnswVectorsFormat();
        }
        
        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            return delegate.fieldsWriter(state);
        }
        
        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
            return delegate.fieldsReader(state);
        }
        
        @Override
        public int getMaxDimensions(String fieldName) {
            return 4096;
        }
    }
}
