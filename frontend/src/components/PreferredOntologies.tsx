import * as React from 'react'
import { useState, Fragment, useCallback } from 'react'
import { ZoomaDatasources } from "../api/ZoomaDatasources"
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig"

import {
  Box,
  Typography,
  Autocomplete,
  TextField,
  Chip,
  Button,
  Stack,
  FormControlLabel,
  Checkbox
} from '@mui/material'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
}

export default function PreferredOntologies({ datasources, datasourceConfig, onConfigChanged }: Props) {
  const [inputValue, setInputValue] = useState('')

  const onSelectOntology = useCallback((val: string | null) => {
    if (!val) return
    let newSources = [...datasourceConfig.preferredOntologies]

    if (newSources.indexOf(val) === -1) {
      newSources.push(val)
    }

    setInputValue('')
    onConfigChanged({
      ...datasourceConfig,
      preferredOntologies: newSources
    })
  }, [datasourceConfig, onConfigChanged])

  const removePreferredOntology = useCallback((s: string) => {
    onConfigChanged({
      ...datasourceConfig,
      preferredOntologies: datasourceConfig.preferredOntologies.filter(src => src !== s)
    })
  }, [datasourceConfig, onConfigChanged])

  const clearAll = useCallback(() => {
    onConfigChanged({
      ...datasourceConfig,
      preferredOntologies: []
    })
  }, [datasourceConfig, onConfigChanged])

  return (
    <Box>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
        Results from these ontologies will be prioritized.
      </Typography>

      <Autocomplete
        options={datasources.searchableOntoNames}
        getOptionLabel={(item: any) => item.displayName || item.name}
        value={null}
        inputValue={inputValue}
        onInputChange={(_, newValue, reason) => {
          if (reason !== 'reset') setInputValue(newValue)
        }}
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

      {datasourceConfig.preferredOntologies.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1.5 }}>
            {datasourceConfig.preferredOntologies.map(source => (
              <Chip
                key={source}
                label={source.toUpperCase()}
                onDelete={() => removePreferredOntology(source)}
                color="success"
                sx={{ fontSize: '0.95rem' }}
              />
            ))}
          </Stack>
          <Button 
            size="small" 
            color="success"
            onClick={clearAll} 
            sx={{ mt: 2, textTransform: 'none' }}
          >
            Clear all
          </Button>
          <FormControlLabel
            control={
              <Checkbox
                checked={datasourceConfig.includeOtherOntologies}
                onChange={(e) => onConfigChanged({ ...datasourceConfig, includeOtherOntologies: e.target.checked })}
                color="success"
              />
            }
            label="Include results from other ontologies"
            sx={{ mt: 1, display: 'block' }}
          />
        </Box>
      )}

      {datasourceConfig.preferredOntologies.length === 0 && (
        <Typography variant="body1" color="text.secondary" sx={{ mt: 2, fontStyle: 'italic' }}>
          No preferred ontologies selected. All ontologies will be searched with equal priority.
        </Typography>
      )}
    </Box>
  )
}
