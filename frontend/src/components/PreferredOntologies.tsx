import * as React from 'react'
import { useState, useCallback, useRef } from 'react'
import { ZoomaDatasources } from "../api/ZoomaDatasources"
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig"

import {
  Alert,
  Box,
  Typography,
  Autocomplete,
  TextField,
  Button,
  FormControlLabel,
  Checkbox,
  Select,
  MenuItem,
  ListItemText,
  List,
  ListItem,
  IconButton,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import type { OntologyPreset } from '../api/ZoomaApi'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
  presets?: OntologyPreset[]
}

function arraysEqual(a: string[], b: string[]) {
  return a.length === b.length && a.every((v, i) => v === b[i])
}

const ID_BADGE_SX = {
  display: 'inline-block',
  backgroundColor: '#00827c',
  color: '#fff',
  borderRadius: '4px',
  px: 0.75,
  py: 0.25,
  fontWeight: 700,
  fontSize: '0.8rem',
  lineHeight: 1.3,
  mr: 1,
  flexShrink: 0,
} as const

export default function PreferredOntologies({ datasources, datasourceConfig, onConfigChanged, presets }: Props) {
  const [inputValue, setInputValue] = useState('')
  const [customMode, setCustomMode] = useState(false)
  const [dragIdx, setDragIdx] = useState<number | null>(null)
  const [overIdx, setOverIdx] = useState<number | null>(null)
  const dragRef = useRef<number | null>(null)

  const ontologies = datasourceConfig.targetOntologies || []

  const matchedPreset = presets?.find(p => arraysEqual(p.ontologies, ontologies))?.name
  const activePreset = matchedPreset ?? (customMode || (ontologies.length > 0 && !matchedPreset) ? '__custom__' : '__all__')

  const onSelectOntology = useCallback((val: string | null) => {
    if (!val) return
    let newSources = [...(datasourceConfig.targetOntologies || [])]
    if (newSources.indexOf(val) === -1) {
      newSources.push(val)
    }
    setInputValue('')
    onConfigChanged({
      ...datasourceConfig,
      targetOntologies: newSources
    })
  }, [datasourceConfig, onConfigChanged])

  const removePreferredOntology = useCallback((s: string) => {
    onConfigChanged({
      ...datasourceConfig,
      targetOntologies: (datasourceConfig.targetOntologies || []).filter(src => src !== s)
    })
  }, [datasourceConfig, onConfigChanged])

  const clearAll = useCallback(() => {
    onConfigChanged({
      ...datasourceConfig,
      targetOntologies: []
    })
  }, [datasourceConfig, onConfigChanged])

  const handleDragStart = (idx: number) => (e: React.DragEvent) => {
    dragRef.current = idx
    setDragIdx(idx)
    e.dataTransfer.effectAllowed = 'move'
  }

  const handleDragOver = (idx: number) => (e: React.DragEvent) => {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    setOverIdx(idx)
  }

  const handleDrop = (idx: number) => (e: React.DragEvent) => {
    e.preventDefault()
    const from = dragRef.current
    if (from === null || from === idx) { setDragIdx(null); setOverIdx(null); return }
    const newList = [...ontologies]
    const [moved] = newList.splice(from, 1)
    newList.splice(idx, 0, moved)
    onConfigChanged({ ...datasourceConfig, targetOntologies: newList })
    setDragIdx(null)
    setOverIdx(null)
  }

  const handleDragEnd = () => { setDragIdx(null); setOverIdx(null) }

  return (
    <Box>
      {presets && presets.length > 0 && (
        <Select
          value={activePreset}
          displayEmpty
          size="small"
          onChange={(e) => {
            const value = e.target.value
            if (value === '__all__') {
              setCustomMode(false)
              onConfigChanged({
                ...datasourceConfig,
                targetOntologies: [],
                includeOtherOntologies: false
              })
              return
            }
            if (value === '__custom__') {
              setCustomMode(true)
              onConfigChanged({
                ...datasourceConfig,
                targetOntologies: [],
                includeOtherOntologies: false
              })
              return
            }
            setCustomMode(false)
            const preset = presets.find(p => p.name === value)
            if (preset) {
              onConfigChanged({
                ...datasourceConfig,
                targetOntologies: [...preset.ontologies],
                includeOtherOntologies: false
              })
            }
          }}
          renderValue={(val) => {
            if (!val || val === '__all__') return 'All ontologies (not recommended)'
            if (val === '__custom__') return 'Custom ontologies'
            return val
          }}
          sx={{ mb: 2, maxWidth: 500, display: 'block' }}
        >
          {presets.map(preset => {
            const ids = preset.ontologies.map(o => o.toUpperCase())
            const secondary = ids.length > 10
              ? ids.slice(0, 10).join(', ') + ` … and ${ids.length - 10} more`
              : ids.join(', ')
            return (
              <MenuItem key={preset.name} value={preset.name}>
                <ListItemText
                  primary={`${preset.name} (${ids.length})`}
                  secondary={secondary}
                  secondaryTypographyProps={{ variant: 'caption', sx: { mt: 0.25 } }}
                />
              </MenuItem>
            )
          })}
          <MenuItem value="__custom__">
            <ListItemText
              primary="Choose custom ontologies"
              secondary="Search and pick individual ontologies"
              secondaryTypographyProps={{ variant: 'caption', sx: { mt: 0.25 } }}
            />
          </MenuItem>
          <MenuItem value="__all__">
            <ListItemText
              primary="All ontologies (not recommended)"
              secondary="Search all ontologies with equal priority"
              secondaryTypographyProps={{ variant: 'caption', sx: { mt: 0.25 } }}
            />
          </MenuItem>
        </Select>
      )}

      {activePreset === '__all__' && (
        <Alert severity="warning" sx={{ mb: 2, maxWidth: 500 }}>
          ZOOMA performs better when given specific target ontologies.
        </Alert>
      )}

      {activePreset === '__custom__' && <Autocomplete
        options={datasources.searchableOntoNames}
        getOptionLabel={(item: any) => item.displayName || item.name}
        value={null}
        inputValue={inputValue}
        onInputChange={(_, newValue, reason) => {
          if (reason !== 'reset') setInputValue(newValue)
        }}
        onChange={(_, val) => onSelectOntology(typeof val === 'string' ? val : val?.name ?? null)}
        renderInput={(params) => (
          <TextField
            {...params}
            placeholder="Search ontologies by name, e.g. EFO"
            variant="outlined"
            sx={{ maxWidth: 500 }}
          />
        )}
      />}

      {ontologies.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Box sx={{ maxHeight: 300, overflowY: 'scroll', border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <List dense disablePadding>
              {ontologies.map((source, idx) => (
                <ListItem
                  key={source}
                  draggable
                  onDragStart={handleDragStart(idx)}
                  onDragOver={handleDragOver(idx)}
                  onDrop={handleDrop(idx)}
                  onDragEnd={handleDragEnd}
                  secondaryAction={
                    <IconButton edge="end" size="small" onClick={() => removePreferredOntology(source)}>
                      <CloseIcon fontSize="small" />
                    </IconButton>
                  }
                  sx={{
                    py: 0.5,
                    pr: 6,
                    opacity: dragIdx === idx ? 0.4 : 1,
                    borderTop: overIdx === idx && dragIdx !== null && dragIdx > idx ? '2px solid #00827c' : '2px solid transparent',
                    borderBottom: overIdx === idx && dragIdx !== null && dragIdx < idx ? '2px solid #00827c' : undefined,
                    transition: 'border-color 0.15s',
                  }}
                >
                  <Box sx={{ display: 'flex', alignItems: 'center', cursor: 'grab', mr: 0.5 }}>
                    <DragIndicatorIcon sx={{ fontSize: '1.1rem', opacity: 0.5 }} />
                  </Box>
                  <Box sx={{ display: 'flex', alignItems: 'center', minWidth: 0 }}>
                    <Box component="span" sx={ID_BADGE_SX}>{source.toUpperCase()}</Box>
                    <Typography variant="body2" color="text.secondary" noWrap>
                      {datasources.nameTitleMap.get(source) || ''}
                    </Typography>
                  </Box>
                </ListItem>
              ))}
            </List>
          </Box>
          <Button
            size="small"
            color="success"
            onClick={clearAll}
            sx={{ mt: 2, textTransform: 'none' }}
          >
            Clear all
          </Button>
        </Box>
      )}

      {activePreset !== '__all__' && ontologies.length > 0 && (
        <FormControlLabel
          control={
            <Checkbox
              checked={datasourceConfig.includeOtherOntologies ?? false}
              onChange={(e) => onConfigChanged({ ...datasourceConfig, includeOtherOntologies: e.target.checked })}
              color="success"
            />
          }
          label="Include results from other ontologies"
          sx={{ mt: 1, display: 'block' }}
        />
      )}
    </Box>
  )
}
