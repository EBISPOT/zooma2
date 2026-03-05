

import React, { useState } from "react";
import * as ZoomaApi from "../api/ZoomaApi";
import { ZoomaDatasources } from "../api/ZoomaDatasources";
import { sources } from '../data/sources.json';
import {
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    TablePagination,
    Paper,
    Checkbox,
    FormControlLabel,
    Typography,
    Box,
    Button,
    Dialog,
    DialogTitle,
    DialogContent,
    IconButton,
    Link,
    CircularProgress,
    Tooltip
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import CloseIcon from '@mui/icons-material/Close';
import ThumbUpIcon from '@mui/icons-material/ThumbUp';
import ThumbUpOutlinedIcon from '@mui/icons-material/ThumbUpOutlined';
import ThumbDownIcon from '@mui/icons-material/ThumbDown';
import ThumbDownOutlinedIcon from '@mui/icons-material/ThumbDownOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';


interface ResultsTableProps {
    results: ZoomaApi.SearchResult[];
    datasources: ZoomaDatasources | undefined;
    searchParams?: ZoomaApi.SearchParams;
}

const ResultsTable: React.FC<ResultsTableProps> = ({ results, datasources, searchParams }) => {
    const [hideUnmapped, setHideUnmapped] = useState(false);
    const [page, setPage] = useState(0);
    const [rowsPerPage, setRowsPerPage] = useState(100);
    const [approved, setApproved] = useState<Set<string>>(new Set());
    // Overrides from thumbs-down: keyed by "propertyValue\tpropertyType"
    const [overrides, setOverrides] = useState<Map<string, ZoomaApi.SearchResult>>(new Map());
    const [excludedByKey, setExcludedByKey] = useState<Map<string, string[]>>(new Map());
    const [loadingKey, setLoadingKey] = useState<string | null>(null);
    const [removedKeys, setRemovedKeys] = useState<Set<string>>(new Set());

    // Apply overrides and filtering, preserving stable keys from original results
    const effectiveResults = results.map(r => {
        const stableKey = resultKey(r);
        const effective = overrides.has(stableKey) ? overrides.get(stableKey)! : r;
        return { ...effective, _key: stableKey };
    });

    const filteredResults = effectiveResults
        .filter(r => !removedKeys.has(r._key))
        .filter(r => (!hideUnmapped) || r.mappingConfidence !== 'Did not map');
    const paginatedResults = filteredResults.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage);

    const hasAnyPropertyType = effectiveResults.some(r => r.propertyType && r.propertyType.trim() !== '');

    const downloadCsv = () => {
        const headers = ['Text to Map', ...(hasAnyPropertyType ? ['Term Type'] : []), 'Term Label', 'Confidence', 'Term ID', 'Source', 'Mapping Type'];
        const rows = filteredResults.map(r => [
            r.propertyValue,
            ...(hasAnyPropertyType ? [r.propertyType] : []),
            r.ontologyTermLabel,
            formatConfidence(r.mappingConfidence),
            shortFormToCurie(r.ontologyTermID),
            r.datasource || '',
            getMappingTypeSummary(r.mappingProvenance)
        ]);
        const csvContent = [headers, ...rows].map(row => row.map(cell => `"${(cell || '').replace(/"/g, '""')}"`).join(',')).join('\n');
        const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'zooma_results.csv';
        a.click();
        URL.revokeObjectURL(url);
    };

    const handleApprove = (key: string, result: ZoomaApi.SearchResult) => {
        if (!approved.has(key) && result.ontologyTermID) {
            ZoomaApi.recordVote(
                result.propertyValue, result.propertyType,
                result.ontologyTermID, result.ontologyTermLabel,
                result.ontologyURI, 'up'
            );
        }
        setApproved(prev => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key); else next.add(key);
            return next;
        });
    };

    const handleReject = async (key: string, result: ZoomaApi.SearchResult) => {
        if (!searchParams) return;
        if (!result.ontologyTermID || result.mappingConfidence === 'Did not map') return;

        // Record downvote
        ZoomaApi.recordVote(
            result.propertyValue, result.propertyType,
            result.ontologyTermID, result.ontologyTermLabel,
            result.ontologyURI, 'down'
        );

        const prevExcluded = excludedByKey.get(key) || [];
        const newExcluded = [...prevExcluded, result.ontologyTermID];

        setLoadingKey(key);
        try {
            const newResults = await ZoomaApi.remapOne(
                searchParams,
                result.propertyValue,
                result.propertyType,
                newExcluded
            );
            setExcludedByKey(prev => new Map(prev).set(key, newExcluded));
            if (newResults.length > 0) {
                setOverrides(prev => new Map(prev).set(key, newResults[0]));
            } else {
                setOverrides(prev => new Map(prev).set(key, {
                    ...result,
                    ontologyTermLabel: result.propertyValue,
                    mappingConfidence: 'Did not map',
                    ontologyTermID: '',
                    ontologyURI: '',
                    datasource: '',
                    mappingProvenance: []
                }));
            }
        } finally {
            setLoadingKey(null);
        }
    };

    const handleRemove = (key: string) => {
        setRemovedKeys(prev => new Set(prev).add(key));
    };

    return (
        <Box>
            <Box display="flex" alignItems="center" gap={2}>
                <FormControlLabel
                    control={
                        <Checkbox
                            checked={hideUnmapped}
                            onChange={() => { setHideUnmapped(!hideUnmapped); setPage(0); }}
                            color="success"
                        />
                    }
                    label="Hide results that did not map"
                />
                <Button
                    variant="outlined"
                    size="small"
                    startIcon={<DownloadIcon />}
                    onClick={downloadCsv}
                >
                    Download CSV
                </Button>
            </Box>
            <TableContainer component={Paper} sx={{ mt: 2 }}>
                <Table size="small">
                    <TableHead>
                        <TableRow>
                            <TableCell sx={{ fontWeight: 'bold' }}>Text to Map</TableCell>
                            {hasAnyPropertyType && <TableCell sx={{ fontWeight: 'bold' }}>Term Type</TableCell>}
                            <TableCell sx={{ fontWeight: 'bold' }}>Term Label</TableCell>
                            <TableCell sx={{ fontWeight: 'bold' }}>Confidence</TableCell>
                            <TableCell sx={{ fontWeight: 'bold' }}>Term ID</TableCell>
                            <TableCell sx={{ fontWeight: 'bold' }}>Source</TableCell>
                            <TableCell sx={{ fontWeight: 'bold' }}>Mapping Type</TableCell>
                            <TableCell sx={{ fontWeight: 'bold', width: 120 }}></TableCell>
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {paginatedResults.map((result) => {
                            const key = result._key;
                            const isApproved = approved.has(key);
                            const isLoading = loadingKey === key;
                            const isMapped = result.mappingConfidence !== 'Did not map' && !!result.ontologyTermID;
                            return (
                                <TableRow key={key} className={isApproved ? 'automatic' : getResultClass(result)}>
                                    <TableCell>{result.propertyValue}</TableCell>
                                    {hasAnyPropertyType && <TableCell>{result.propertyType}</TableCell>}
                                    <TableCell>{result.ontologyTermLabel}</TableCell>
                                    <TableCell>{formatConfidence(result.mappingConfidence)}</TableCell>
                                    <TableCell><TermIdLink termId={result.ontologyTermID} ontology={result.ontologyURI} /></TableCell>
                                    <TableCell><Datasource datasources={datasources} uri={result.datasource} /></TableCell>
                                    <TableCell><MappingProvenance provenance={result.mappingProvenance} /></TableCell>
                                    <TableCell sx={{ whiteSpace: 'nowrap', py: 0 }}>
                                        <Tooltip title="Approve">
                                            <IconButton size="small" onClick={() => handleApprove(key, result)}
                                                sx={{ color: isApproved ? '#2e7d32' : undefined }}>
                                                {isApproved ? <ThumbUpIcon fontSize="small" /> : <ThumbUpOutlinedIcon fontSize="small" />}
                                            </IconButton>
                                        </Tooltip>
                                        <Tooltip title="Try another mapping">
                                            <span>
                                                <IconButton size="small" onClick={() => handleReject(key, result)}
                                                    disabled={isLoading || !isMapped || !searchParams}>
                                                    {isLoading
                                                        ? <CircularProgress size={18} />
                                                        : <ThumbDownOutlinedIcon fontSize="small" />}
                                                </IconButton>
                                            </span>
                                        </Tooltip>
                                        <Tooltip title="Remove row">
                                            <IconButton size="small" onClick={() => handleRemove(key)}>
                                                <DeleteOutlineIcon fontSize="small" />
                                            </IconButton>
                                        </Tooltip>
                                    </TableCell>
                                </TableRow>
                            );
                        })}
                    </TableBody>
                </Table>
                <TablePagination
                    component="div"
                    count={filteredResults.length}
                    page={page}
                    onPageChange={(_, newPage) => setPage(newPage)}
                    rowsPerPage={rowsPerPage}
                    onRowsPerPageChange={(e) => { setRowsPerPage(parseInt(e.target.value, 10)); setPage(0); }}
                    rowsPerPageOptions={[50, 100, 250, 500]}
                />
            </TableContainer>
            <Box mt={2}>
                <Typography variant="body2">
                    <b>Stats:</b> {effectiveResults.length} properties &emsp;&emsp;
                    {effectiveResults.filter(r => parseFloat(r.mappingConfidence) >= 0.9).length} high (&ge;0.9) &emsp;&emsp;
                    {effectiveResults.filter(r => { const c = parseFloat(r.mappingConfidence); return c > 0 && c < 0.9; }).length} low (&lt;0.9) &emsp;&emsp;
                    {effectiveResults.filter(r => r.mappingConfidence === 'Did not map').length} unmapped
                </Typography>
            </Box>
        </Box>
    );
};

export default ResultsTable;

function resultKey(r: ZoomaApi.SearchResult): string {
    return (r.propertyValue || '') + '\t' + (r.propertyType || '') + '\t' + (r.ontologyTermID || '');
}

function formatConfidence(confidence: string): string {
    if (!confidence || confidence === 'Did not map') return confidence;
    const num = parseFloat(confidence);
    if (isNaN(num)) return confidence;
    return num.toFixed(2);
}

function shortFormToCurie(shortForm: string): string {
    if (!shortForm) return '';
    // Convert EFO_0000734 → EFO:0000734 (replace first _ with :)
    const idx = shortForm.search(/_\d/);
    if (idx !== -1) {
        return shortForm.substring(0, idx) + ':' + shortForm.substring(idx + 1);
    }
    return shortForm;
}

function TermIdLink({ termId, ontology }: { termId: string; ontology: string }) {
    if (!termId) return <span>-</span>;
    const curie = shortFormToCurie(termId);
    // Link to OLS4 search, which reliably finds terms by short form
    const olsUrl = `https://www.ebi.ac.uk/ols4/search?q=${encodeURIComponent(termId)}&exact=true`;
    return (
        <Link href={olsUrl} target="_blank" rel="noopener noreferrer" sx={{ fontFamily: 'monospace', fontSize: '0.85em' }}>
            {curie}
        </Link>
    );
}

function getMappingTypeSummary(provenance?: ZoomaApi.MappingProvenanceStep[]): string {
    if (!provenance || provenance.length === 0) return '-';
    const methods = Array.from(new Set(provenance.map(step => {
        switch (step.method) {
            case 'semantic': return 'Embedding';
            case 'lexical': return 'Lexical';
            case 'curated': return 'Curated';
            case 'cross_reference': return 'Cross-ref';
            default: return step.method;
        }
    })));
    return methods.join(', ');
}


function Datasource(props: any) {
    const { datasources, uri } = props;

    if (datasources === undefined) {
        return <span>{uri}</span>;
    }

    if (datasources.loadedOntologyURIs.indexOf(uri) !== -1) {
        const name = datasources.uriNameMap.get(uri);
        return (
            <a href={'//www.ebi.ac.uk/ols/ontologies/' + name} target="_blank" rel="noopener noreferrer">
                <img src="images/ols-logo.jpg" style={{ height: '20px' }} alt={name} />
                &nbsp;
                {name}
            </a>
        );
    }

    const source = sources.filter((s: any) => s.url === uri)[0];
    if (source !== undefined) {
        return (
            <a href={source.linkTo} target="_blank" rel="noopener noreferrer">
                <img src={source.logo} style={{ height: '20px' }} alt={source.name} />
                &nbsp;
                {source.name}
            </a>
        );
    }

    return <span>{uri}</span>;
}


function getResultClass(result: any) {
    const c = parseFloat(result.mappingConfidence);
    if (isNaN(c)) return 'unmapped';
    if (c >= 0.9) return 'automatic';
    return 'curation';
}


function MappingProvenance({ provenance }: { provenance?: ZoomaApi.MappingProvenanceStep[] }) {
    const [open, setOpen] = useState(false);

    if (!provenance || provenance.length === 0) {
        return <span>-</span>;
    }

    // Get unique methods for concise display
    const methods = Array.from(new Set(provenance.map(step => {
        switch (step.method) {
            case 'semantic': return 'Embedding';
            case 'lexical': return 'Lexical';
            case 'curated': return 'Curated';
            case 'cross_reference': return 'Cross-ref';
            default: return step.method;
        }
    })));

    const summary = methods.join(', ');

    const formatMatchType = (step: ZoomaApi.MappingProvenanceStep) => {
        if (step.method === 'semantic') {
            return step.similarity ? `${(step.similarity * 100).toFixed(0)}% similar` : 'embedding';
        }
        if (step.matchType === 'exact_label') return 'exact label';
        if (step.matchType === 'synonym') return 'synonym';
        if (step.matchType === 'database_mapping') return 'database';
        if (step.matchType === 'OXO_MAPPING') {
            const confidence = step.confidence ? `${(step.confidence * 100).toFixed(0)}%` : '';
            return confidence ? `OXO (${confidence})` : 'OXO';
        }
        if (step.matchType === 'OLS_LLM_SIMILAR') {
            const confidence = step.confidence ? `${(step.confidence * 100).toFixed(0)}%` : '';
            return confidence ? `LLM similar (${confidence})` : 'LLM similar';
        }
        if (step.matchType === 'OBSOLETE_REPLACEMENT') return 'obsolete → replacement';
        return step.matchType || '-';
    };

    return (
        <>
            <Link
                component="button"
                variant="body2"
                onClick={() => setOpen(true)}
                sx={{ cursor: 'pointer' }}
            >
                {summary}
            </Link>
            <Dialog 
                open={open} 
                onClose={() => setOpen(false)}
                maxWidth="md"
                fullWidth
            >
                <DialogTitle sx={{ m: 0, p: 2 }}>
                    Mapping Provenance
                    <IconButton
                        aria-label="close"
                        onClick={() => setOpen(false)}
                        sx={{
                            position: 'absolute',
                            right: 8,
                            top: 8,
                            color: (theme) => theme.palette.grey[500],
                        }}
                    >
                        <CloseIcon />
                    </IconButton>
                </DialogTitle>
                <DialogContent dividers>
                    <TableContainer>
                        <Table size="small">
                            <TableHead>
                                <TableRow>
                                    <TableCell><strong>Step</strong></TableCell>
                                    <TableCell><strong>Method</strong></TableCell>
                                    <TableCell><strong>Match Type</strong></TableCell>
                                    <TableCell><strong>Input</strong></TableCell>
                                    <TableCell><strong>Matched Text</strong></TableCell>
                                    <TableCell><strong>Target</strong></TableCell>
                                    <TableCell><strong>Source</strong></TableCell>
                                    <TableCell><strong>Model</strong></TableCell>
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {provenance.map((step, idx) => (
                                    <TableRow key={idx}>
                                        <TableCell>{idx + 1}</TableCell>
                                        <TableCell>
                                            {step.method === 'semantic' && '🔍 Embedding'}
                                            {step.method === 'lexical' && '📝 Lexical'}
                                            {step.method === 'curated' && '📚 Curated'}
                                            {step.method === 'cross_reference' && '🔗 Cross-ref'}
                                            {!['semantic', 'lexical', 'curated', 'cross_reference'].includes(step.method) && step.method}
                                        </TableCell>
                                        <TableCell>{formatMatchType(step)}</TableCell>
                                        <TableCell>{step.input}</TableCell>
                                        <TableCell>
                                            {step.matchedText && step.matchedText !== step.input 
                                                ? step.matchedText 
                                                : <span style={{ color: '#999' }}>—</span>}
                                        </TableCell>
                                        <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.85em' }}>
                                            {step.target}
                                        </TableCell>
                                        <TableCell>{step.source || '-'}</TableCell>
                                        <TableCell sx={{ fontSize: '0.85em' }}>
                                            {step.model || '-'}
                                        </TableCell>
                                    </TableRow>
                                ))}
                            </TableBody>
                        </Table>
                    </TableContainer>
                </DialogContent>
            </Dialog>
        </>
    );
}



