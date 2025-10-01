
import { Fragment, ChangeEvent, useEffect, useState } from "react";
import ResultsTable from "../components/ResultsTable";
import * as ZoomaApi from '../api/ZoomaApi';
import { getDatasources, ZoomaDatasources } from "../api/ZoomaDatasources";
import { runInThisContext } from "vm";
import DatasourcesModal from "../components/Datasources";
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig";
import * as React from 'react';
import Datasources from "../components/Datasources";
import FileSaver from 'file-saver';
import Header from "../components/Header";
import { Grid, Button, Box, Typography } from '@mui/material';

export default function Home(props) {

  const [datasources, setDatasources] = useState<ZoomaDatasources | undefined>(undefined)
  const [datasourceConfig, setDatasourceConfig] = useState<ZoomaDatasourceConfig | undefined>(undefined)
  const [query, setQuery] = useState<string>('')
  const [searching, setSearching] = useState<boolean>(false)
  const [progress, setProgress] = useState<number>(0)
  const [results, setResults] = useState<ZoomaApi.SearchResult[]>([])
  const [tsv, setTsv] = useState<string>('')
  const [showDatasourceModal, setShowDatasourceModal] = useState<boolean>(false)

  useEffect(() => {
    loadDatasources()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function loadDatasources() {
    let datasources = await getDatasources()

    let datasourceConfig: ZoomaDatasourceConfig = {
      doNotSearchDatasources: false,

      excludedDatasources: [],
      unrankedDatasources: datasources.datasourceNames,
      rankedDatasources: [],

      doNotSearchOntologies: false,
      ontologySources: []
    }

    setDatasources(datasources)
    setDatasourceConfig(datasourceConfig)
  }

  const onEditQuery = (e: ChangeEvent) => {
    let newValue = (e.target as any).value
    setQuery(newValue)
  }

  const onClickAnnotate = async () => {

    let properties = query
      .split('\n')
      .map(line => line.split('\t'))
      .map(tokens => ({ propertyValue: tokens[0], propertyType: tokens[1] }))

    let requiredSources = [...(datasourceConfig!.unrankedDatasources), ...(datasourceConfig!.rankedDatasources)]
    let preferredSources = datasourceConfig!.rankedDatasources
    let ontologySources = datasourceConfig!.ontologySources
    let doNotSearchDatasources = datasourceConfig!.doNotSearchDatasources
    let doNotSearchOntologies = datasourceConfig!.doNotSearchOntologies

    let searchParams: ZoomaApi.SearchParams = {
      properties, requiredSources, preferredSources, ontologySources, doNotSearchDatasources, doNotSearchOntologies
    }

    setSearching(true)

    let results = await ZoomaApi.search(searchParams)

    // let tsv = JSON.stringify(results, Object.keys(results[0]), '\t')
    let tsv = ''

    setSearching(false)
    setResults(results)
    setTsv(tsv)
  }

  const onClickClear = () => {
    setQuery('')
    setResults([])
    setTsv('')
  }

  const onClickShowExamples = () => {
    setQuery(examples)
  }

  const onClickDatasources = () => {
    setShowDatasourceModal(true)
  }

  const onDatasourceConfigChanged = (config: ZoomaDatasourceConfig) => {
    setDatasourceConfig(config)
  }

  const onDatasourcesModalDone = () => {
    setShowDatasourceModal(false)
  }

  const onDownloadTSV = () => {
    var blob = new Blob([tsv], { type: 'text/csv' })
    FileSaver.saveAs(blob, 'results.tsv')
  }

  return (
    <Fragment>
      <Header section="home" />
      <main>
        <Grid container spacing={2}>
          <Grid item xs={12}>
            <Typography variant="h4">Query</Typography>
            <p>ZOOMA maps one or more strings of text to ontology terms. You can add one string (e.g. <i>Homo sapiens</i>) per
              line. If you also have a type for your term (e.g. <i>organism</i>), put this after the term,
              separated by a tab.</p>
          </Grid>
          <Grid item xs={12}>
            <Grid container spacing={1}>
              <Grid item xs={12}>
                <Grid container justifyContent="flex-end">
                  <Grid item>
                    <a onClick={onClickShowExamples} style={{ cursor: 'pointer' }}>
                      Show me some examples...
                    </a>
                  </Grid>
                </Grid>
              </Grid>
            </Grid>
            <Grid container spacing={1}>
              <Grid item xs={12}>
                <textarea style={{ minHeight: '300px', width: '100%', border: '1px solid #ddd', padding: '8px', borderRadius: '4px' }} value={query}
                  onKeyDown={(e) => {
                    if (e.key === "Tab") {
                      e.preventDefault();

                      const target = e.target as any;
                      const start = target.selectionStart;
                      const end = target.selectionEnd;
                      const value = target.value;

                      // Insert a tab character
                      target.value = value.substring(0, start) + "\t" + value.substring(end);

                      // Move cursor after the tab
                      target.selectionStart = target.selectionEnd = start + 1;

                      // Fire React's onChange so state updates
                      const event = new Event("input", { bubbles: true });
                      target.dispatchEvent(event);
                    }
                  }}

                  onChange={onEditQuery}></textarea>
              </Grid>
            </Grid>
          </Grid>
          <Grid item xs={12}>
            <Typography variant="h4">Datasources</Typography>
            <p>ZOOMA uses curated mappings from selected datasources (more
              preferred), and searches ontologies directly (less preferred). Here, you can select
              which curated datasources to use, optionally ranked in order of preference. You can also
              select which ontologies to search directly. By default all ontologies in OLS are searched.</p>
          </Grid>
          <Grid item xs={12}>
            {datasources && datasourceConfig && (
              <Datasources
                datasources={datasources}
                datasourceConfig={datasourceConfig}
                onConfigChanged={onDatasourceConfigChanged}
              />
            )}
          </Grid>
          <Grid item xs={12}>
            <Box display="flex" justifyContent="center" alignItems="center" mt={2}>
              <button
              className="button-primary text-lg font-bold self-center"
                disabled={searching}
                onClick={onClickAnnotate}
              >
                Annotate
              </button>
              {results.length > 0 &&
              <Fragment>
              &nbsp;
              <button
              className="button-secondary text-lg font-bold self-center"
                onClick={onClickClear}
              >
                Clear
              </button>
              </Fragment>
}
            </Box>
          </Grid>
          <Grid item xs={12}>
            <h3>Results</h3>
            <Grid container spacing={1} alignItems="center">
              <Grid item xs={8}>
                <p>The table below shows a report describing how ZOOMA annotates text terms supplied above.</p>
              </Grid>
              <Grid item xs={4} style={{ textAlign: 'right' }}>
                <img style={{ cursor: 'pointer' }} onClick={onDownloadTSV} src="https://www.ebi.ac.uk/web_guidelines/images/icons/EBI-FileFormats/File%20format%20icons/file_TSV.png" />
              </Grid>
            </Grid>
            <ResultsTable results={results} datasources={datasources} />
          </Grid>
        </Grid>
      </main>
    </Fragment>
  );
}

var examples =
  `Bright nuclei
Agammaglobulinemia 2\tphenotype
Reduction in IR-induced 53BP1 foci in HeLa\tcell
Impaired cell migration with increased protrusive activity\tphenotype
C57Black/6\tstrain
nuclei stay close together
Retinal cone dystrophy 3B\tdisease
segregation problems/chromatin bridges/lagging chromosomes/multiple DNA masses
Segawa syndrome autosomal recessive\tphenotype
BRCA1\tgene
Deafness, autosomal dominant 17\tphenotype
cooked broccoli\tcompound
Amyloidosis, familial visceral\tphenotype
Spastic paraplegia 10\tphenotype
Epilepsy, progressive myoclonic 1B\tphenotype
Big cells
Cardiomyopathy, dilated, 1S\tphenotype
Long QT syndrome 3/6, digenic\tdisease
Lung adenocarcinoma disease state
doxycycline 130 nanomolar compound
left tibia\torganism part
CD4-positive
cerebellum\torganism part
hematology traits\tgwas trait
nifedipine 0.025 micromolar compound
Microtubule clumps
`

