

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
    Paper,
    Checkbox,
    FormControlLabel,
    Typography,
    Box,
    Dialog,
    DialogTitle,
    DialogContent,
    IconButton,
    Link
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';


interface ResultsTableProps {
    results: ZoomaApi.SearchResult[];
    datasources: ZoomaDatasources | undefined;
}

const ResultsTable: React.FC<ResultsTableProps> = ({ results, datasources }) => {
    const [hideUnmapped, setHideUnmapped] = useState(false);

    // console.dir(results)

    return (
        <Box>
            <FormControlLabel
                control={
                    <Checkbox
                        checked={hideUnmapped}
                        onChange={() => setHideUnmapped(!hideUnmapped)}
                        color="success"
                    />
                }
                label="Hide results that did not map"
            />
            <TableContainer component={Paper} sx={{ mt: 2 }}>
                <Table size="small">
                    <TableHead>
                        <TableRow>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">Term Type</span>
                            </TableCell>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">Term Value</span>
                            </TableCell>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">Ontology Class Label</span>
                            </TableCell>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">Mapping Confidence</span>
                            </TableCell>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">Ontology Class ID</span>
                            </TableCell>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">Source</span>
                            </TableCell>
                            <TableCell className="context-help">
                                <span className="context-help-label clickable" data-icon="?">How Mapped</span>
                            </TableCell>
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {results
                            .filter(result => (!hideUnmapped) || result.mappingConfidence !== 'Did not map')
                            .map((result, idx) => (
                                <TableRow key={idx} className={getResultClass(result)}>
                                    <TableCell>{result.propertyType}</TableCell>
                                    <TableCell>{result.propertyValue}</TableCell>
                                    <TableCell>{result.ontologyTermLabel}</TableCell>
                                    {/* <TableCell>{result.ontologyTermSynonyms}</TableCell> */}
                                    <TableCell>{result.mappingConfidence}</TableCell>
                                    <TableCell>{result.ontologyTermID}</TableCell>
                                    <TableCell><Datasource datasources={datasources} uri={result.datasource} /></TableCell>
                                    <TableCell><MappingProvenance provenance={result.mappingProvenance} /></TableCell>
                                </TableRow>
                            ))}
                    </TableBody>
                </Table>
            </TableContainer>
            <Box mt={2}>
                <Typography variant="body2">
                    <b>Stats:</b> {results.length} properties &emsp;&emsp;
                    {results.filter(r => r.mappingConfidence === 'High').length} high &emsp;&emsp;
                    {results.filter(r => r.mappingConfidence === 'Good').length} good  &emsp;&emsp;
                    {results.filter(r => r.mappingConfidence === 'Medium').length} medium  &emsp;&emsp;
                    {results.filter(r => r.mappingConfidence === 'Low').length} low &emsp;&emsp;
                    {results.filter(r => r.mappingConfidence === 'Did not map').length} unmapped
                </Typography>
            </Box>
        </Box>
    );
};

export default ResultsTable;


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
    return (
        {
            High: 'automatic',
            Good: 'curation',
            Medium: 'curation',
            Low: 'curation',
        } as Record<string, string>
    )[result.mappingConfidence] || 'unmapped';
}


function MappingProvenance({ provenance }: { provenance?: ZoomaApi.MappingProvenanceStep[] }) {
    const [open, setOpen] = useState(false);

    if (!provenance || provenance.length === 0) {
        return <span>-</span>;
    }

    // Get unique methods for concise display
    const methods = [...new Set(provenance.map(step => {
        switch (step.method) {
            case 'semantic': return 'Semantic';
            case 'lexical': return 'Lexical';
            case 'curated': return 'Curated';
            case 'cross_reference': return 'Cross-ref';
            default: return step.method;
        }
    }))];

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
                                            {step.method === 'semantic' && '🔍 '}
                                            {step.method === 'lexical' && '📝 '}
                                            {step.method === 'curated' && '📚 '}
                                            {step.method === 'cross_reference' && '🔗 '}
                                            {step.method}
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



