import * as React from 'react'
import { useState, useCallback } from 'react'
import { ZoomaDatasources } from "../api/ZoomaDatasources"
import { ZoomaDatasourceConfig } from "../api/ZoomaDatasourceConfig"
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  closestCenter,
  type DragStartEvent,
  type DragEndEvent,
} from "@dnd-kit/core"
import {
  SortableContext,
  useSortable,
  horizontalListSortingStrategy,
  arrayMove,
} from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"

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
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
}

function SortableChip({ id, index, onDelete }: { id: string; index: number; onDelete: () => void }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id })

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
    cursor: 'grab',
  }

  return (
    <Box ref={setNodeRef} style={style} {...attributes} {...listeners} sx={{ display: 'inline-flex' }}>
      <Chip
        icon={<DragIndicatorIcon sx={{ fontSize: '1.1rem', opacity: 0.7 }} />}
        label={`${index + 1}. ${id.toUpperCase()}`}
        onDelete={onDelete}
        color="success"
        sx={{ fontSize: '0.95rem', cursor: 'grab' }}
      />
    </Box>
  )
}

export default function PreferredOntologies({ datasources, datasourceConfig, onConfigChanged }: Props) {
  const [inputValue, setInputValue] = useState('')
  const [activeId, setActiveId] = useState<string | null>(null)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  )

  const ontologies = datasourceConfig.targetOntologies || []

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

  const handleDragStart = (event: DragStartEvent) => {
    setActiveId(String(event.active.id))
  }

  const handleDragEnd = (event: DragEndEvent) => {
    setActiveId(null)
    const { active, over } = event
    if (!over || active.id === over.id) return

    const oldIndex = ontologies.indexOf(String(active.id))
    const newIndex = ontologies.indexOf(String(over.id))
    if (oldIndex === -1 || newIndex === -1) return

    onConfigChanged({
      ...datasourceConfig,
      targetOntologies: arrayMove([...ontologies], oldIndex, newIndex)
    })
  }

  return (
    <Box>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
        ZOOMA performs better when given specific target ontologies. Drag to set priority.
      </Typography>

      <Autocomplete
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
      />

      {ontologies.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragStart={handleDragStart}
            onDragEnd={handleDragEnd}
          >
            <SortableContext items={ontologies} strategy={horizontalListSortingStrategy}>
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1.5 }}>
                {ontologies.map((source, index) => (
                  <SortableChip
                    key={source}
                    id={source}
                    index={index}
                    onDelete={() => removePreferredOntology(source)}
                  />
                ))}
              </Stack>
            </SortableContext>
            <DragOverlay>
              {activeId ? (
                <Chip
                  icon={<DragIndicatorIcon sx={{ fontSize: '1.1rem', opacity: 0.7 }} />}
                  label={`${ontologies.indexOf(activeId) + 1}. ${activeId.toUpperCase()}`}
                  color="success"
                  sx={{ fontSize: '0.95rem', boxShadow: 3, cursor: 'grabbing' }}
                />
              ) : null}
            </DragOverlay>
          </DndContext>
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
                checked={datasourceConfig.includeOtherOntologies ?? true}
                onChange={(e) => onConfigChanged({ ...datasourceConfig, includeOtherOntologies: e.target.checked })}
                color="success"
              />
            }
            label="Include results from other ontologies"
            sx={{ mt: 1, display: 'block' }}
          />
        </Box>
      )}

      {ontologies.length === 0 && (
        <Typography variant="body1" color="text.secondary" sx={{ mt: 2, fontStyle: 'italic' }}>
          No preferred ontologies selected. All ontologies will be searched with equal priority.
        </Typography>
      )}
    </Box>
  )
}
