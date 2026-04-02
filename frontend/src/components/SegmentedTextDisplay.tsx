import React, { useMemo } from 'react';
import { Box } from '@mui/material';
import { TextSegment, SearchResult } from '../api/ZoomaApi';

interface SegmentedTextDisplayProps {
    originalText: string;
    segments: TextSegment[];
    results: SearchResult[];
    onSegmentClick: (segmentText: string) => void;
    onRemovePhrase: (segmentText: string) => void;
    removedPhrases: Set<string>;
    activeSegment?: string;
}

// Same confidence thresholds / colors as the table rows in index.css
function getSegmentColor(results: SearchResult[], segmentText: string, removedPhrases: Set<string>): string | null {
    if (removedPhrases.has(segmentText.toLowerCase())) return null;
    const matching = results.filter(r => r.textToMap?.toLowerCase() === segmentText.toLowerCase());
    if (matching.length === 0) return null;
    const best = Math.max(...matching.map(r => parseFloat(r.mappingConfidence)).filter(c => !isNaN(c)));
    if (isNaN(best)) return '#f5f5f5'; // unmapped
    if (best >= 0.9) return '#DEFFDE'; // automatic (green)
    if (best >= 0.2) return '#FFFDC4'; // curation (yellow)
    return '#f5f5f5'; // low-score (grey)
}

const SegmentedTextDisplay: React.FC<SegmentedTextDisplayProps> = ({
    originalText,
    segments,
    results,
    onSegmentClick,
    onRemovePhrase,
    removedPhrases,
    activeSegment
}) => {
    // Build non-overlapping spans sorted by start position
    const sortedSegments = useMemo(() => {
        const sorted = [...segments].sort((a, b) => a.start - b.start);
        const result: TextSegment[] = [];
        let lastEnd = 0;
        for (const seg of sorted) {
            if (seg.start >= lastEnd) {
                result.push(seg);
                lastEnd = seg.end;
            }
        }
        return result;
    }, [segments]);

    // Build interleaved plain text + highlighted spans
    const parts = useMemo(() => {
        const result: { text: string; isSegment: boolean; segmentText?: string }[] = [];
        let pos = 0;
        for (const seg of sortedSegments) {
            if (seg.start > pos) {
                result.push({ text: originalText.substring(pos, seg.start), isSegment: false });
            }
            result.push({ text: originalText.substring(seg.start, seg.end), isSegment: true, segmentText: seg.text });
            pos = seg.end;
        }
        if (pos < originalText.length) {
            result.push({ text: originalText.substring(pos), isSegment: false });
        }
        return result;
    }, [originalText, sortedSegments]);

    return (
        <Box sx={{
            p: 2,
            border: '1px solid #e0e0e0',
            borderRadius: 2,
            bgcolor: '#fafafa',
            mb: 3,
            lineHeight: 2,
            fontSize: '0.95rem',
            wordBreak: 'break-word',
        }}>
            <Box component="span">
                {parts.map((part, i) => {
                    if (!part.isSegment) {
                        return <React.Fragment key={i}>{part.text}</React.Fragment>;
                    }
                    const segLower = part.segmentText?.toLowerCase() || '';
                    if (removedPhrases.has(segLower)) {
                        return <React.Fragment key={i}>{part.text}</React.Fragment>;
                    }
                    const isActive = activeSegment?.toLowerCase() === segLower;
                    const bgColor = getSegmentColor(results, part.segmentText!, removedPhrases);
                    const hasResult = bgColor !== null;
                    const pillBg = isActive ? '#a5d6a7' : (hasResult ? bgColor! : '#e0e0e0');
                    return (
                        <Box
                            key={i}
                            component="span"
                            sx={{
                                display: 'inline-flex',
                                alignItems: 'center',
                                bgcolor: pillBg,
                                borderRadius: '999px',
                                border: hasResult ? 'none' : '1.5px dashed #aaa',
                                pl: '10px',
                                pr: '4px',
                                py: '1px',
                                mx: '2px',
                                fontSize: '0.9em',
                                lineHeight: 1.6,
                                verticalAlign: 'middle',
                                cursor: 'pointer',
                                transition: 'filter 0.15s',
                                '&:hover': { filter: 'brightness(0.92)' },
                            }}
                            onClick={() => onSegmentClick(part.segmentText!)}
                        >
                            <Box component="span">{part.text}</Box>
                            <Box
                                component="span"
                                onClick={(e: React.MouseEvent) => {
                                    e.stopPropagation();
                                    onRemovePhrase(part.segmentText!);
                                }}
                                sx={{
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    ml: '5px',
                                    width: '16px',
                                    height: '16px',
                                    borderRadius: '50%',
                                    bgcolor: 'rgba(0,0,0,0.15)',
                                    color: 'rgba(0,0,0,0.55)',
                                    fontSize: '12px',
                                    lineHeight: 1,
                                    flexShrink: 0,
                                    userSelect: 'none',
                                    cursor: 'pointer',
                                    transition: 'background-color 0.15s, color 0.15s',
                                    '&:hover': {
                                        bgcolor: '#c62828',
                                        color: 'white',
                                    },
                                }}
                            >
                                ×
                            </Box>
                        </Box>
                    );
                })}
            </Box>
        </Box>
    );
};

export default SegmentedTextDisplay;
