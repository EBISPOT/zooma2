

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
    Box
} from '@mui/material';


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
                        color="primary"
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
                                    {/* <TableCell>{result.ontologyIRI}</TableCell> */}
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



