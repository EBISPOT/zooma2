import { Fragment, ChangeEvent, useEffect, useState } from "react";
import ResultsTable from "../components/ResultsTable";
import * as ZoomaApi from '../api/ZoomaApi';
import { getDatasources, ZoomaDatasources } from "../api/ZoomaDatasources";
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig";
import * as React from 'react';
import Datasources from "../components/Datasources";
import PreferredOntologies from "../components/PreferredOntologies";
import CsvImportDialog from "../components/CsvImportDialog";
import FileSaver from 'file-saver';
import Header from "../components/Header";
import { 
  Button, Box, Typography, 
  Accordion, AccordionSummary, AccordionDetails,
  CircularProgress, Chip, IconButton, Tooltip
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import DownloadIcon from '@mui/icons-material/Download';

export default function Home() {

  const [datasources, setDatasources] = useState<ZoomaDatasources | undefined>(undefined)
  const [datasourceConfig, setDatasourceConfig] = useState<ZoomaDatasourceConfig | undefined>(undefined)
  const [query, setQuery] = useState<string>('')
  const [searching, setSearching] = useState<boolean>(false)
  const [results, setResults] = useState<ZoomaApi.SearchResult[]>([])

  useEffect(() => {
    loadDatasources()
  }, [])

  useEffect(() => {
    if (datasourceConfig) {
      localStorage.setItem('zooma2_datasourceConfig', JSON.stringify(datasourceConfig))
    }
  }, [datasourceConfig])

  async function loadDatasources() {
    let datasources = await getDatasources()

    const savedConfig = localStorage.getItem('zooma2_datasourceConfig')
    let datasourceConfig: ZoomaDatasourceConfig
    
    if (savedConfig) {
      try {
        datasourceConfig = JSON.parse(savedConfig)
        datasourceConfig.unrankedDatasources = datasourceConfig.unrankedDatasources.filter(
          ds => datasources.datasourceNames.includes(ds)
        )
        datasourceConfig.rankedDatasources = datasourceConfig.rankedDatasources.filter(
          ds => datasources.datasourceNames.includes(ds)
        )
        datasourceConfig.excludedDatasources = datasourceConfig.excludedDatasources.filter(
          ds => datasources.datasourceNames.includes(ds)
        )
        const allSaved = [...datasourceConfig.unrankedDatasources, ...datasourceConfig.rankedDatasources, ...datasourceConfig.excludedDatasources]
        const newDatasources = datasources.datasourceNames.filter(ds => !allSaved.includes(ds))
        datasourceConfig.unrankedDatasources = [...datasourceConfig.unrankedDatasources, ...newDatasources]
      } catch (e) {
        datasourceConfig = getDefaultConfig(datasources)
      }
    } else {
      datasourceConfig = getDefaultConfig(datasources)
    }

    setDatasources(datasources)
    setDatasourceConfig(datasourceConfig)
  }

  function getDefaultConfig(datasources: ZoomaDatasources): ZoomaDatasourceConfig {
    return {
      doNotSearchDatasources: false,
      excludedDatasources: [],
      unrankedDatasources: datasources.datasourceNames,
      rankedDatasources: [],
      doNotSearchOntologies: false,
      ontologySources: [],
      preferredOntologies: [],
      useLlmSearch: true,
      llmModel: 'text-embedding-3-small'
    }
  }

  const onEditQuery = (e: ChangeEvent) => {
    setQuery((e.target as any).value)
  }

  const getQueryCount = () => query.split('\n').filter(line => line.trim()).length

  const onClickAnnotate = async () => {
    let properties = query
      .split('\n')
      .filter(line => line.trim())
      .map(line => line.split('\t'))
      .map(tokens => ({ propertyValue: tokens[0], propertyType: tokens[1] }))

    if (properties.length === 0) return

    setSearching(true)

    let searchParams: ZoomaApi.SearchParams = {
      properties,
      requiredSources: [...(datasourceConfig!.unrankedDatasources), ...(datasourceConfig!.rankedDatasources)],
      preferredSources: datasourceConfig!.rankedDatasources,
      ontologySources: datasourceConfig!.ontologySources,
      doNotSearchDatasources: datasourceConfig!.doNotSearchDatasources,
      doNotSearchOntologies: datasourceConfig!.doNotSearchOntologies,
      preferredOntologies: datasourceConfig!.preferredOntologies,
      useLlmSearch: true,
      llmModel: 'text-embedding-3-small'
    }

    let results = await ZoomaApi.search(searchParams)
    setSearching(false)
    setResults(results)
  }

  const onClickClear = () => {
    setQuery('')
    setResults([])
  }

  const onClickShowExamples = () => {
    setQuery(examples)
  }

  const onDatasourceConfigChanged = (config: ZoomaDatasourceConfig) => {
    setDatasourceConfig(config)
  }

  const onDownloadTSV = () => {
    if (results.length === 0) return
    const headers = ['propertyValue', 'propertyType', 'ontologyTermLabel', 'ontologyTermID', 'ontologyURI', 'mappingConfidence', 'datasource']
    const rows = results.map(r => headers.map(h => r[h] || '').join('\t'))
    const tsv = [headers.join('\t'), ...rows].join('\n')
    var blob = new Blob([tsv], { type: 'text/tsv' })
    FileSaver.saveAs(blob, 'zooma_results.tsv')
  }

  const hasDatasourceSettings = datasourceConfig && (
    datasourceConfig.excludedDatasources.length > 0 ||
    datasourceConfig.rankedDatasources.length > 0 ||
    datasourceConfig.doNotSearchDatasources
  )

  const hasOntologySettings = datasourceConfig && (
    datasourceConfig.preferredOntologies.length > 0 ||
    datasourceConfig.doNotSearchOntologies
  )

  return (
    <Fragment>
      <Header section="home" />
      <main>
        <Box sx={{ maxWidth: 1100, mx: 'auto', px: 3, py: 4 }}>
          
          {/* Query Section */}
          <Box sx={{ mb: 4 }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
              <Typography variant="h5" sx={{ fontWeight: 500, display: 'flex', alignItems: 'center' }}>
                <Box component="span" sx={{ 
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  width: 28, height: 28, borderRadius: '50%', bgcolor: '#2e7d32', color: 'white',
                  fontSize: '0.9rem', fontWeight: 600, mr: 1.5, flexShrink: 0
                }}>1</Box>
                Enter terms to annotate
              </Typography>
              <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
                <CsvImportDialog onImport={(terms) => setQuery(prev => prev ? prev + '\n' + terms : terms)} />
                <Typography 
                  variant="body2" 
                  sx={{ color: '#2e7d32', cursor: 'pointer', '&:hover': { textDecoration: 'underline' } }}
                  onClick={onClickShowExamples}
                >
                  Load examples
                </Typography>
              </Box>
            </Box>
            
            <textarea 
              style={{ 
                width: '100%',
                minHeight: '160px',
                padding: '16px',
                fontSize: '15px',
                fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
                border: '2px solid #e0e0e0',
                borderRadius: '8px',
                resize: 'vertical',
                outline: 'none',
                transition: 'border-color 0.2s',
              }}
              value={query}
              placeholder={"Enter terms to annotate, one per line\n\nOptionally add a type after a tab:\nHomo sapiens\nheart disease\tdisease\ndoxycycline\tcompound"}
              onFocus={(e) => e.target.style.borderColor = '#2e7d32'}
              onBlur={(e) => e.target.style.borderColor = '#e0e0e0'}
              onKeyDown={(e) => {
                if (e.key === "Tab") {
                  e.preventDefault();
                  const target = e.target as any;
                  const start = target.selectionStart;
                  const end = target.selectionEnd;
                  const value = target.value;
                  target.value = value.substring(0, start) + "\t" + value.substring(end);
                  target.selectionStart = target.selectionEnd = start + 1;
                  const event = new Event("input", { bubbles: true });
                  target.dispatchEvent(event);
                }
              }}
              onChange={onEditQuery}
            />
          </Box>

          {/* Settings Section */}
          <Box sx={{ mb: 4 }}>
            <Accordion 
              disableGutters
              sx={{ 
                bgcolor: 'transparent',
                boxShadow: 'none', 
                '&:before': { display: 'none' },
                '& .MuiAccordionSummary-root': { minHeight: 48, px: 0 },
                '& .MuiAccordionSummary-content': { my: 1 }
              }}
            >
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Typography variant="h5" sx={{ fontWeight: 500, display: 'flex', alignItems: 'center' }}>
                  <Box component="span" sx={{ 
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    width: 28, height: 28, borderRadius: '50%', bgcolor: '#9e9e9e', color: 'white',
                    fontSize: '0.9rem', fontWeight: 600, mr: 1.5, flexShrink: 0
                  }}>2</Box>
                  Where to get mappings?
                  {hasDatasourceSettings && (
                    <Chip label="customized" size="small" sx={{ ml: 1.5, height: 22, fontSize: '0.75rem' }} />
                  )}
                </Typography>
              </AccordionSummary>
              <AccordionDetails sx={{ px: 0, pt: 1, pl: 5 }}>
                {datasources && datasourceConfig && (
                  <Datasources
                    datasources={datasources}
                    datasourceConfig={datasourceConfig}
                    onConfigChanged={onDatasourceConfigChanged}
                  />
                )}
              </AccordionDetails>
            </Accordion>

            <Accordion 
              disableGutters
              sx={{ 
                bgcolor: 'transparent',
                boxShadow: 'none', 
                '&:before': { display: 'none' },
                '& .MuiAccordionSummary-root': { minHeight: 48, px: 0 },
                '& .MuiAccordionSummary-content': { my: 1 }
              }}
            >
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Typography variant="h5" sx={{ fontWeight: 500, display: 'flex', alignItems: 'center' }}>
                  <Box component="span" sx={{ 
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    width: 28, height: 28, borderRadius: '50%', bgcolor: '#9e9e9e', color: 'white',
                    fontSize: '0.9rem', fontWeight: 600, mr: 1.5, flexShrink: 0
                  }}>3</Box>
                  Which ontologies to map to?
                  {hasOntologySettings && (
                    <Chip label="customized" size="small" sx={{ ml: 1.5, height: 22, fontSize: '0.75rem' }} />
                  )}
                </Typography>
              </AccordionSummary>
              <AccordionDetails sx={{ px: 0, pt: 1, pl: 5 }}>
                {datasources && datasourceConfig && (
                  <PreferredOntologies
                    datasources={datasources}
                    datasourceConfig={datasourceConfig}
                    onConfigChanged={onDatasourceConfigChanged}
                  />
                )}
              </AccordionDetails>
            </Accordion>

            {/* Annotate Button */}
            <Box sx={{ display: 'flex', alignItems: 'center', mt: 3, gap: 1.5 }}>
              <Button
                variant="contained"
                color="success"
                size="large"
                onClick={onClickAnnotate}
                disabled={searching || getQueryCount() === 0}
                sx={{ 
                  px: 4, 
                  py: 1.25,
                  fontSize: '1rem',
                  textTransform: 'none',
                  borderRadius: 2,
                  boxShadow: 'none',
                  '&:hover': { boxShadow: 'none' }
                }}
              >
                {searching ? (
                  <>
                    <CircularProgress size={20} sx={{ mr: 1, color: 'white' }} />
                    Searching...
                  </>
                ) : (
                  `Annotate${getQueryCount() > 0 ? ` ${getQueryCount()} term${getQueryCount() > 1 ? 's' : ''}` : ''}`
                )}
              </Button>
              {(results.length > 0 || query) && (
                <Button 
                  variant="text" 
                  onClick={onClickClear}
                  sx={{ textTransform: 'none', color: 'text.secondary' }}
                >
                  Clear
                </Button>
              )}
            </Box>
          </Box>

          {/* Results Section */}
          {(results.length > 0 || searching) && (
            <Box sx={{ borderTop: '1px solid #e0e0e0', pt: 3 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                <Typography variant="h5" sx={{ fontWeight: 500 }}>
                  Results {!searching && `(${results.length})`}
                </Typography>
                {results.length > 0 && (
                  <Tooltip title="Download as TSV">
                    <IconButton onClick={onDownloadTSV} size="small">
                      <DownloadIcon />
                    </IconButton>
                  </Tooltip>
                )}
              </Box>
              
              {searching ? (
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
                  <CircularProgress />
                </Box>
              ) : (
                <ResultsTable results={results} datasources={datasources} />
              )}
            </Box>
          )}

        </Box>
      </main>
    </Fragment>
  );
}

const examples = `Homo sapiens\torganism
heart disease\tdisease
BRCA1\tgene
cerebellum\torganism part
doxycycline\tcompound
CD4-positive\tcell type
lung adenocarcinoma\tdisease
C57Black/6\tstrain
Bright nuclei
Big cells
`

