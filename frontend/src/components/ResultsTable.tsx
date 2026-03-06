

import React, { useState, useMemo } from "react";
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
    Typography,
    Box,
    Button,
    Dialog,
    DialogTitle,
    DialogContent,
    DialogActions,
    IconButton,
    Link,
    CircularProgress,
    Tooltip,
    Radio
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ThumbUpIcon from '@mui/icons-material/ThumbUp';
import ThumbUpOutlinedIcon from '@mui/icons-material/ThumbUpOutlined';
import ThumbDownIcon from '@mui/icons-material/ThumbDown';
import ThumbDownOutlinedIcon from '@mui/icons-material/ThumbDownOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';


interface ResultsTableProps {
    results: ZoomaApi.SearchResult[];
    resultsVersion: number;
    datasources: ZoomaDatasources | undefined;
    searchParams?: ZoomaApi.SearchParams;
    inputProperties?: { textToMap: string; propertyType: string }[];
    searching?: boolean;
}

const ResultsTable: React.FC<ResultsTableProps> = ({ results, resultsVersion, datasources, searchParams, inputProperties, searching }) => {

    const [page, setPage] = useState(0);
    const [rowsPerPage, setRowsPerPage] = useState(100);
    const [approved, setApproved] = useState<Set<string>>(new Set());
    // Overrides from thumbs-down: keyed by "textToMap\tpropertyType"
    const [overrides, setOverrides] = useState<Map<string, ZoomaApi.SearchResult>>(new Map());
    const [removedKeys, setRemovedKeys] = useState<Set<string>>(new Set());
    // Alternative mappings modal state
    const [alternativesModal, setAlternativesModal] = useState<{
        key: string;
        originalResult: ZoomaApi.SearchResult;
        candidates: ZoomaApi.SearchResult[];
    } | null>(null);
    const [alternativesLoading, setAlternativesLoading] = useState(false);
    const [selectedAlternative, setSelectedAlternative] = useState<string | null>(null);

    // Build a set of textToMap+type keys that have returned results
    const returnedKeys = useMemo(() => 
        new Set(results.map(r => (r.textToMap || '') + '\t' + (r.propertyType || ''))),
        [results, resultsVersion]
    );

    // Generate in-progress placeholder rows for properties that haven't returned yet
    const inProgressRows = useMemo(() => 
        (searching && inputProperties) ? inputProperties
            .filter(p => !returnedKeys.has((p.textToMap || '') + '\t' + (p.propertyType || '')))
            .map(p => ({
                textToMap: p.textToMap,
                propertyType: p.propertyType,
                ontologyTermLabel: '',
                ontologyTermSynonyms: '',
                mappingConfidence: 'In progress' as string,
                ontologyTermID: '',
                ontologyURI: '',
                datasource: '',
                _inProgress: true as const,
                mappingProvenance: undefined,
            })) : [],
        [searching, inputProperties, returnedKeys]
    );

    // Sort by input order: build an index map from textToMap+type to input position
    const inputOrderMap = useMemo(() => {
        const map = new Map<string, number>();
        if (inputProperties) {
            inputProperties.forEach((p, i) => {
                const key = (p.textToMap || '') + '\t' + (p.propertyType || '');
                if (!map.has(key)) map.set(key, i);
            });
        }
        return map;
    }, [inputProperties]);

    // Combine, sort, apply overrides
    const effectiveResults = useMemo(() => {
        const allRows = [...results, ...inProgressRows];
        const sortedRows = inputOrderMap.size > 0
            ? allRows.sort((a, b) => {
                const ka = (a.textToMap || '') + '\t' + (a.propertyType || '');
                const kb = (b.textToMap || '') + '\t' + (b.propertyType || '');
                return (inputOrderMap.get(ka) ?? 9999) - (inputOrderMap.get(kb) ?? 9999);
            })
            : allRows;
        return sortedRows.map(r => {
            const stableKey = resultKey(r);
            const effective = overrides.has(stableKey) ? overrides.get(stableKey)! : r;
            return { ...effective, _key: stableKey, _inProgress: (r as any)._inProgress };
        });
    }, [results, resultsVersion, inProgressRows, inputOrderMap, overrides]);

    // Build set of property keys (value+type) that have at least one approved row
    const approvedPropertyKeys = useMemo(() => {
        const propKeys = new Set<string>();
        approved.forEach(key => {
            const lastTab = key.lastIndexOf('\t');
            if (lastTab > 0) propKeys.add(key.substring(0, lastTab));
        });
        return propKeys;
    }, [approved]);

    const filteredResults = useMemo(() => effectiveResults
        .filter(r => !removedKeys.has(r._key))
        .filter(r => {
            // If this property has an approved row, hide all non-approved rows for it
            if (approved.has(r._key)) return true;
            const propKey = (r.textToMap || '') + '\t' + (r.propertyType || '');
            if (approvedPropertyKeys.has(propKey)) return false;
            return true;
        })
,
        [effectiveResults, removedKeys, approved, approvedPropertyKeys]
    );
    const paginatedResults = filteredResults.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage);

    const hasAnyPropertyType = effectiveResults.some(r => r.propertyType && r.propertyType.trim() !== '');

    const handleApprove = (key: string, result: ZoomaApi.SearchResult) => {
        if (!approved.has(key) && result.ontologyTermID) {
            ZoomaApi.recordVote(
                result.textToMap, result.propertyType,
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

        // Record downvote immediately
        ZoomaApi.recordVote(
            result.textToMap, result.propertyType,
            result.ontologyTermID, result.ontologyTermLabel,
            result.ontologyURI, 'down'
        );

        // Collect existing table results for this property to merge into candidates
        const propKey = (result.textToMap || '') + '\t' + (result.propertyType || '');
        const existingResults = results.filter(r =>
            (r.textToMap || '') + '\t' + (r.propertyType || '') === propKey
            && r.ontologyTermID
            && r.mappingConfidence !== 'Did not map'
        );

        // Open modal and fetch all candidates
        setAlternativesModal({ key, originalResult: result, candidates: [] });
        setSelectedAlternative(result.ontologyTermID);
        setAlternativesLoading(true);
        try {
            const fetched = await ZoomaApi.fetchAllCandidates(
                searchParams,
                result.textToMap,
                result.propertyType,
                []
            );
            // Merge: start with existing table results, then add fetched results not already present
            const seen = new Set(existingResults.map(r => r.ontologyTermID));
            const merged = [...existingResults, ...fetched.filter(c => !seen.has(c.ontologyTermID))];
            // Sort by confidence descending
            merged.sort((a, b) => parseFloat(b.mappingConfidence) - parseFloat(a.mappingConfidence));
            setAlternativesModal({ key, originalResult: result, candidates: merged });
        } finally {
            setAlternativesLoading(false);
        }
    };

    const handleSelectAlternative = () => {
        if (!alternativesModal || !selectedAlternative) return;
        const { key, originalResult, candidates } = alternativesModal;
        const selected = candidates.find(c => c.ontologyTermID === selectedAlternative);
        if (!selected) return;

        // If user picked the same mapping, just close
        if (selected.ontologyTermID === originalResult.ontologyTermID) {
            setAlternativesModal(null);
            setSelectedAlternative(null);
            return;
        }

        // Record upvote for the newly selected mapping
        ZoomaApi.recordVote(
            selected.textToMap, selected.propertyType,
            selected.ontologyTermID, selected.ontologyTermLabel,
            selected.ontologyURI, 'up'
        );

        // Update the row with the selected candidate and mark as approved (green)
        setOverrides(prev => new Map(prev).set(key, selected));
        setApproved(prev => new Set(prev).add(key));
        setAlternativesModal(null);
        setSelectedAlternative(null);
    };

    const handleRemove = (key: string) => {
        setRemovedKeys(prev => new Set(prev).add(key));
    };

    return (
        <Box>
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
                            <TableCell sx={{ fontWeight: 'bold' }}>Prediction Type</TableCell>
                            <TableCell sx={{ fontWeight: 'bold', width: 120 }}></TableCell>
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {paginatedResults.map((result) => {
                            const key = result._key;
                            const isApproved = approved.has(key);
                            const isInProgress = result._inProgress;
                            const isMapped = result.mappingConfidence !== 'Did not map' && !isInProgress && !!result.ontologyTermID;
                            return (
                                <TableRow key={key} className={isInProgress ? 'in-progress' : isApproved ? 'automatic' : getResultClass(result)} sx={{
                                    ...(isInProgress ? { opacity: 0.5 } : {}),
                                    transition: 'filter 0.15s ease',
                                    '&:hover': { filter: 'brightness(0.95)' },
                                }}>
                                    <TableCell>{result.textToMap}</TableCell>
                                    {hasAnyPropertyType && <TableCell>{result.propertyType}</TableCell>}
                                    <TableCell>{isInProgress ? '' : result.ontologyTermLabel}</TableCell>
                                    <TableCell>{isInProgress ? <CircularProgress size={14} /> : formatConfidence(result.mappingConfidence)}</TableCell>
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
                                        <Tooltip title="Select alternative mapping">
                                            <span>
                                                <IconButton size="small" onClick={() => handleReject(key, result)}
                                                    disabled={!isMapped || !searchParams}>
                                                    <ThumbDownOutlinedIcon fontSize="small" />
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
                    {effectiveResults.filter(r => r._inProgress).length > 0 && <>{effectiveResults.filter(r => r._inProgress).length} in progress &emsp;&emsp;</>}
                    {effectiveResults.filter(r => parseFloat(r.mappingConfidence) >= 0.9).length} high (&ge;0.9) &emsp;&emsp;
                    {effectiveResults.filter(r => { const c = parseFloat(r.mappingConfidence); return c > 0 && c < 0.9; }).length} low (&lt;0.9) &emsp;&emsp;
                    {effectiveResults.filter(r => r.mappingConfidence === 'Did not map').length} unmapped
                </Typography>
            </Box>
            {/* Alternative mappings modal */}
            <Dialog
                open={alternativesModal !== null}
                onClose={() => { setAlternativesModal(null); setSelectedAlternative(null); }}
                maxWidth="md"
                fullWidth
            >
                <DialogTitle sx={{ m: 0, p: 2 }}>
                    Select Mapping for "{alternativesModal?.originalResult.textToMap}"
                    <IconButton
                        aria-label="close"
                        onClick={() => { setAlternativesModal(null); setSelectedAlternative(null); }}
                        sx={{ position: 'absolute', right: 8, top: 8, color: (theme) => theme.palette.grey[500] }}
                    >
                        <CloseIcon />
                    </IconButton>
                </DialogTitle>
                <DialogContent dividers>
                    {alternativesLoading ? (
                        <Box display="flex" justifyContent="center" py={4}>
                            <CircularProgress />
                        </Box>
                    ) : alternativesModal && alternativesModal.candidates.length === 0 ? (
                        <Typography color="text.secondary" py={2}>No alternative mappings found.</Typography>
                    ) : (
                        <TableContainer>
                            <Table size="small">
                                <TableHead>
                                    <TableRow>
                                        <TableCell padding="checkbox"></TableCell>
                                        <TableCell sx={{ fontWeight: 'bold' }}>Term Label</TableCell>
                                        <TableCell sx={{ fontWeight: 'bold' }}>Confidence</TableCell>
                                        <TableCell sx={{ fontWeight: 'bold' }}>Term ID</TableCell>
                                        <TableCell sx={{ fontWeight: 'bold' }}>Source</TableCell>
                                        <TableCell sx={{ fontWeight: 'bold' }}>Prediction Type</TableCell>
                                    </TableRow>
                                </TableHead>
                                <TableBody>
                                    {alternativesModal?.candidates.map((candidate, idx) => (
                                        <TableRow
                                            key={candidate.ontologyTermID || idx}
                                            hover
                                            onClick={() => setSelectedAlternative(candidate.ontologyTermID)}
                                            sx={{ cursor: 'pointer' }}
                                            selected={selectedAlternative === candidate.ontologyTermID}
                                        >
                                            <TableCell padding="checkbox">
                                                <Radio
                                                    size="small"
                                                    checked={selectedAlternative === candidate.ontologyTermID}
                                                    onChange={() => setSelectedAlternative(candidate.ontologyTermID)}
                                                />
                                            </TableCell>
                                            <TableCell>{candidate.ontologyTermLabel}</TableCell>
                                            <TableCell>{formatConfidence(candidate.mappingConfidence)}</TableCell>
                                            <TableCell><TermIdLink termId={candidate.ontologyTermID} ontology={candidate.ontologyURI} /></TableCell>
                                            <TableCell><Datasource datasources={datasources} uri={candidate.datasource} /></TableCell>
                                            <TableCell>{getMappingTypeSummary(candidate.mappingProvenance)}</TableCell>
                                        </TableRow>
                                    ))}
                                </TableBody>
                            </Table>
                        </TableContainer>
                    )}
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => { setAlternativesModal(null); setSelectedAlternative(null); }}>
                        Cancel
                    </Button>
                    <Button
                        variant="contained"
                        disabled={!selectedAlternative || alternativesLoading}
                        onClick={handleSelectAlternative}
                    >
                        Confirm
                    </Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
};

export default ResultsTable;

function resultKey(r: ZoomaApi.SearchResult): string {
    return (r.textToMap || '') + '\t' + (r.propertyType || '') + '\t' + (r.ontologyTermID || '');
}

function formatConfidence(confidence: string): string {
    if (!confidence || confidence === 'Did not map') return confidence;
    const num = parseFloat(confidence);
    if (isNaN(num)) return confidence;
    return num.toFixed(4);
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
    // Link directly to OLS4 term page using the full IRI
    let olsUrl: string;
    if (ontology) {
        const prefix = termId.replace(/_\d.*$/, '').toLowerCase();
        olsUrl = `https://www.ebi.ac.uk/ols4/ontologies/${prefix}/classes/${encodeURIComponent(encodeURIComponent(ontology))}`;
    } else {
        olsUrl = `https://www.ebi.ac.uk/ols4/search?q=${encodeURIComponent(termId)}&exact=true`;
    }
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
            <a href={'https://www.ebi.ac.uk/ols4/ontologies/' + name} target="_blank" rel="noopener noreferrer">
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



