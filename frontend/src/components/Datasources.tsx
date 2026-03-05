import * as React from 'react'
import { useState, Fragment, useCallback } from 'react'
import { ZoomaDatasources } from "../api/ZoomaDatasources"
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig"

import {
  Box,
  Typography,
  FormControlLabel,
  Checkbox,
  Chip,
  Button,
  Autocomplete,
  TextField,
  Stack,
  Divider
} from '@mui/material'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
}

export default function Datasources({ datasources, datasourceConfig, onConfigChanged }: Props) {
  const [ontoAutocomplete, setOntoAutocomplete] = useState<string>('')

  // All active datasources (not excluded)
  const activeDatasources = [
    ...datasourceConfig.unrankedDatasources,
    ...datasourceConfig.rankedDatasources
  ]

  const toggleDatasource = useCallback((ds: string) => {
    const isExcluded = datasourceConfig.excludedDatasources.includes(ds)
    
    if (isExcluded) {
      // Move from excluded to unranked
      onConfigChanged({
        ...datasourceConfig,
        excludedDatasources: datasourceConfig.excludedDatasources.filter(d => d !== ds),
        unrankedDatasources: [...datasourceConfig.unrankedDatasources, ds]
      })
    } else {
      // Move to excluded
      onConfigChanged({
        ...datasourceConfig,
        excludedDatasources: [...datasourceConfig.excludedDatasources, ds],
        unrankedDatasources: datasourceConfig.unrankedDatasources.filter(d => d !== ds),
        rankedDatasources: datasourceConfig.rankedDatasources.filter(d => d !== ds)
      })
    }
  }, [datasourceConfig, onConfigChanged])

  const togglePriority = useCallback((ds: string) => {
    const isRanked = datasourceConfig.rankedDatasources.includes(ds)
    
    if (isRanked) {
      // Move from ranked to unranked
      onConfigChanged({
        ...datasourceConfig,
        rankedDatasources: datasourceConfig.rankedDatasources.filter(d => d !== ds),
        unrankedDatasources: [...datasourceConfig.unrankedDatasources, ds]
      })
    } else {
      // Move from unranked to ranked
      onConfigChanged({
        ...datasourceConfig,
        unrankedDatasources: datasourceConfig.unrankedDatasources.filter(d => d !== ds),
        rankedDatasources: [...datasourceConfig.rankedDatasources, ds]
      })
    }
  }, [datasourceConfig, onConfigChanged])

  const selectAll = useCallback(() => {
    onConfigChanged({
      ...datasourceConfig,
      excludedDatasources: [],
      unrankedDatasources: datasources.datasourceNames,
      rankedDatasources: []
    })
  }, [datasources, datasourceConfig, onConfigChanged])

  const selectNone = useCallback(() => {
    onConfigChanged({
      ...datasourceConfig,
      excludedDatasources: datasources.datasourceNames,
      unrankedDatasources: [],
      rankedDatasources: []
    })
  }, [datasources, datasourceConfig, onConfigChanged])

  const onSelectOntology = useCallback((val: string | null) => {
    if (!val) return
    let newSources = [...datasourceConfig.ontologySources]

    if (newSources.indexOf(val) === -1) {
      newSources.push(val)
    }

    setOntoAutocomplete('')
    onConfigChanged({
      ...datasourceConfig,
      ontologySources: newSources
    })
  }, [datasourceConfig, onConfigChanged])

  const removeOntologySource = useCallback((s: string) => {
    onConfigChanged({
      ...datasourceConfig,
      ontologySources: datasourceConfig.ontologySources.filter(src => src !== s)
    })
  }, [datasourceConfig, onConfigChanged])

  const onChangeDoNotSearchDatasources = useCallback((checked: boolean) => {
    onConfigChanged({
      ...datasourceConfig,
      doNotSearchDatasources: checked
    })
  }, [datasourceConfig, onConfigChanged])

  const onChangeDoNotSearchOntologies = useCallback((checked: boolean) => {
    onConfigChanged({
      ...datasourceConfig,
      doNotSearchOntologies: checked
    })
  }, [datasourceConfig, onConfigChanged])

  return (
    <Box>
      {/* Curated Datasources Section */}
      <Box sx={{ mb: 4 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
          <Typography variant="body1" color="text.secondary">
            Curated mapping databases
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button size="small" color="success" onClick={selectAll} sx={{ textTransform: 'none' }}>
              Select all
            </Button>
            <Button size="small" color="success" onClick={selectNone} sx={{ textTransform: 'none' }}>
              Select none
            </Button>
          </Box>
        </Box>

        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mb: 2 }}>
          {datasources.datasourceNames.map(ds => {
            const isActive = activeDatasources.includes(ds)
            const isRanked = datasourceConfig.rankedDatasources.includes(ds)
            const displayName = datasources.nameTitleMap.get(ds) || ds
            
            return (
              <Chip
                key={ds}
                label={displayName}
                onClick={() => toggleDatasource(ds)}
                onDelete={isActive ? () => togglePriority(ds) : undefined}
                deleteIcon={
                  isRanked 
                    ? <span style={{ fontSize: '16px', marginLeft: '-4px', color: '#ffc107' }}>★</span>
                    : <span style={{ fontSize: '14px', marginLeft: '-4px', color: '#ffc107', opacity: 0.4 }}>☆</span>
                }
                variant={isActive ? "filled" : "outlined"}
                color={isActive ? "success" : "default"}
                sx={{ 
                  fontSize: '0.95rem',
                  py: 0.5,
                  opacity: isActive ? 1 : 0.6,
                  '& .MuiChip-deleteIcon': { 
                    color: '#ffc107 !important'
                  }
                }}
              />
            )
          })}
        </Box>

        <Typography variant="body2" color="text.secondary">
          Click to include/exclude • Click ☆ to prioritize
        </Typography>
      </Box>

      <Divider sx={{ my: 3 }} />

      {/* Ontology Sources Section */}
      <Box>
        <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
          Search specific ontologies directly (optional)
        </Typography>

        <Autocomplete
          options={datasources.searchableOntoNames}
          getOptionLabel={(item: any) => item.displayName || item.name}
          value={null}
          onChange={(_, val) => onSelectOntology(typeof val === 'string' ? val : val?.name)}
          renderInput={(params) => (
            <TextField
              {...params}
              placeholder="Search ontologies by name, e.g. EFO"
              variant="outlined"
              sx={{ maxWidth: 500 }}
            />
          )}
        />

        {datasourceConfig.ontologySources.length > 0 && (
          <Stack direction="row" spacing={1} sx={{ mt: 2, flexWrap: 'wrap', gap: 1 }}>
            {datasourceConfig.ontologySources.map(source => (
              <Chip
                key={source}
                label={source}
                onDelete={() => removeOntologySource(source)}
              />
            ))}
          </Stack>
        )}

        <FormControlLabel
          sx={{ mt: 2 }}
          control={
            <Checkbox
              checked={datasourceConfig.doNotSearchOntologies}
              onChange={(_, checked) => onChangeDoNotSearchOntologies(checked)}
            />
          }
          label={<Typography variant="body1">Skip ontology search (only search existing mappings)</Typography>}
        />
      </Box>
    </Box>
  )
}


