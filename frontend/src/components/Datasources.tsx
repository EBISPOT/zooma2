import * as React from 'react'
import { useState, Fragment, useCallback } from 'react'
import { ZoomaDatasources } from "../api/ZoomaDatasources"
import DragAndDropLists, { List } from "./DragAndDropLists"
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig"

import {
  Grid,
  Box,
  Typography,
  FormControlLabel,
  Checkbox,
  Link,
  Autocomplete,
  TextField
} from '@mui/material'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
}

export default function Datasources({ datasources, datasourceConfig, onConfigChanged }: Props) {
  const [ontoAutocomplete, setOntoAutocomplete] = useState<string>('')

  console.log('datasources are')
  console.dir(datasources)

  const lists: List[] = [
    {
      title: 'Excluded',
      entries: datasourceConfig.excludedDatasources.map(ds => ({
        id: ds,
        content: <Fragment>{datasources.nameTitleMap.get(ds) || ds}</Fragment>
      })),
    },
    {
      title: 'Unranked',
      entries: datasourceConfig.unrankedDatasources.map(ds => ({
        id: ds,
        content: <Fragment>{datasources.nameTitleMap.get(ds) || ds}</Fragment>
      })),
    },
    {
      title: 'Ranked',
      entries: datasourceConfig.rankedDatasources.map(ds => ({
        id: ds,
        content: <Fragment>{datasources.nameTitleMap.get(ds) || ds}</Fragment>
      }))
    }
  ]

  const onDatasourceListsChanged = useCallback((lists: List[]) => {
    let [excludedDatasources, unrankedDatasources, rankedDatasources] = lists

    let newConfig: ZoomaDatasourceConfig = {
      ...datasourceConfig,
      excludedDatasources: excludedDatasources.entries.map(d => d.id),
      unrankedDatasources: unrankedDatasources.entries.map(d => d.id),
      rankedDatasources: rankedDatasources.entries.map(d => d.id)
    }

    onConfigChanged(newConfig)
  }, [datasourceConfig, onConfigChanged])

  const onSelectOntology = useCallback((val: string | null) => {
    if (!val) return
    let newSources = [...datasourceConfig.ontologySources]

    if (newSources.indexOf(val) === -1) {
      newSources.push(val)
    }

    let newConfig: ZoomaDatasourceConfig = {
      ...datasourceConfig,
      ontologySources: newSources
    }

    setOntoAutocomplete('')
    onConfigChanged(newConfig)
  }, [datasourceConfig, onConfigChanged])

  const onChangeDoNotSearchDatasources = useCallback((checked: boolean) => {
    let newConfig: ZoomaDatasourceConfig = {
      ...datasourceConfig,
      doNotSearchDatasources: checked
    }

    onConfigChanged(newConfig)
  }, [datasourceConfig, onConfigChanged])

  const onChangeDoNotSearchOntologies = useCallback((checked: boolean) => {
    let newConfig: ZoomaDatasourceConfig = {
      ...datasourceConfig,
      doNotSearchOntologies: checked
    }

    onConfigChanged(newConfig)
  }, [datasourceConfig, onConfigChanged])

  const removeOntologySource = useCallback((s: string) => {
    let newSources = [...datasourceConfig.ontologySources]
    let i = newSources.indexOf(s)

    if (i !== -1) {
      newSources.splice(i, 1)
    }

    let newConfig: ZoomaDatasourceConfig = {
      ...datasourceConfig,
      ontologySources: newSources
    }

    onConfigChanged(newConfig)
  }, [datasourceConfig, onConfigChanged])

  const excludeAll = useCallback(() => {
    let config = datasourceConfig

    let excluded = [
      ...config.excludedDatasources,
      ...config.unrankedDatasources,
      ...config.rankedDatasources
    ]

    let newConfig: ZoomaDatasourceConfig = {
      ...datasourceConfig,
      excludedDatasources: excluded,
      unrankedDatasources: [],
      rankedDatasources: []
    }

    onConfigChanged(newConfig)
  }, [datasourceConfig, onConfigChanged])

  return (
    <Fragment>
      <Box sx={{ border: '1px solid #777', p: 1.5 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <Typography variant="h6" component="h4" gutterBottom>
              1. Curated Datasources
            </Typography>

            <FormControlLabel
              control={
                <Checkbox
                  checked={datasourceConfig.doNotSearchDatasources}
                  onChange={(_, checked) => onChangeDoNotSearchDatasources(checked)}
                />
              }
              label="Don't search in any datasources"
            />

            <DragAndDropLists lists={lists} onChange={onDatasourceListsChanged} />

            <Box mt={1}>
              <Link component="button" onClick={excludeAll}>Exclude all</Link>
            </Box>
          </Grid>

          <Grid item xs={12} md={6}>
            <Typography variant="h6" component="h4" gutterBottom>
              2. Ontology Sources
            </Typography>

            <FormControlLabel
              control={
                <Checkbox
                  checked={datasourceConfig.doNotSearchOntologies}
                  onChange={(_, checked) => onChangeDoNotSearchOntologies(checked)}
                />
              }
              label="Don't search in any ontologies"
            />

            <Autocomplete
              options={datasources.searchableOntoNames}
              getOptionLabel={(item: any) => item.displayName || item.name}
              value={ontoAutocomplete || null}
              onChange={(_, val) => onSelectOntology(typeof val === 'string' ? val : val?.name)}
              renderInput={(params) => (
                <TextField
                  {...params}
                  placeholder="Search ontologies by name, e.g. EFO or Experimental Factor Ontology"
                  variant="outlined"
                  sx={{ width: 500 }}
                />
              )}
            />

            <Box mt={2}>
              {datasourceConfig.ontologySources.map(source => (
                <div key={source}>
                  <Link component="button" onClick={() => removeOntologySource(source)}>x</Link>
                  &nbsp;
                  {source}
                </div>
              ))}
            </Box>
          </Grid>
        </Grid>
      </Box>
    </Fragment>
  )
}


