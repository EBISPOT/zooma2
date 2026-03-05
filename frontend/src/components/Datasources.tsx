import * as React from 'react'
import { useCallback } from 'react'
import { ZoomaDatasources } from "../api/ZoomaDatasources"
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig"

import {
  Box,
  Typography,
  FormControlLabel,
  Chip,
  Button,
  Stack,
  Divider,
  Radio,
  RadioGroup
} from '@mui/material'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
}

export default function Datasources({ datasources, datasourceConfig, onConfigChanged }: Props) {

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

  const onChangeDoNotSearchOntologies = useCallback((doNotSearch: boolean) => {
    onConfigChanged({
      ...datasourceConfig,
      doNotSearchOntologies: doNotSearch
    })
  }, [datasourceConfig, onConfigChanged])

  return (
    <Box>
      {/* Curated Datasources Section */}
      <Box sx={{ mb: 4 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
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

      {/* Ontologies Section */}
      <Box>
        <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 2 }}>
          Ontologies
        </Typography>

        <RadioGroup
          value={datasourceConfig.doNotSearchOntologies ? 'no' : 'yes'}
          onChange={(_, val) => onChangeDoNotSearchOntologies(val === 'no')}
        >
          <FormControlLabel
            value="yes"
            control={<Radio color="success" />}
            label="Search ontologies in the Ontology Lookup Service (OLS)"
          />
          <FormControlLabel
            value="no"
            control={<Radio color="success" />}
            label="Do not search ontologies in OLS; only use curated mappings"
          />
        </RadioGroup>
      </Box>
    </Box>
  )
}


