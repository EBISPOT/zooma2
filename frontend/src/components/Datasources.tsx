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
  FormControlLabel,
  Chip,
  Button,
  Divider,
  Radio,
  RadioGroup
} from '@mui/material'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'

interface Props {
  datasources: ZoomaDatasources
  datasourceConfig: ZoomaDatasourceConfig
  onConfigChanged: (config: ZoomaDatasourceConfig) => void
}

function SortableDatasourceChip({ id, index, displayName, onToggle }: {
  id: string; index: number; displayName: string; onToggle: () => void
}) {
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
        label={`${index + 1}. ${displayName}`}
        onDelete={onToggle}
        color="success"
        sx={{ fontSize: '0.95rem', py: 0.5, cursor: 'grab' }}
      />
    </Box>
  )
}

export default function Datasources({ datasources, datasourceConfig, onConfigChanged }: Props) {
  const [activeId, setActiveId] = useState<string | null>(null)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  )

  // Ranked datasources are the prioritized, ordered list
  const ranked = datasourceConfig.rankedDatasources

  const toggleDatasource = useCallback((ds: string) => {
    const isExcluded = datasourceConfig.excludedDatasources.includes(ds)
    
    if (isExcluded) {
      // Move from excluded to ranked (append at end)
      onConfigChanged({
        ...datasourceConfig,
        excludedDatasources: datasourceConfig.excludedDatasources.filter(d => d !== ds),
        unrankedDatasources: datasourceConfig.unrankedDatasources.filter(d => d !== ds),
        rankedDatasources: [...datasourceConfig.rankedDatasources, ds]
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

  const selectAll = useCallback(() => {
    onConfigChanged({
      ...datasourceConfig,
      excludedDatasources: [],
      unrankedDatasources: [],
      rankedDatasources: datasources.datasourceNames
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

  const handleDragStart = (event: DragStartEvent) => {
    setActiveId(String(event.active.id))
  }

  const handleDragEnd = (event: DragEndEvent) => {
    setActiveId(null)
    const { active, over } = event
    if (!over || active.id === over.id) return

    const oldIndex = ranked.indexOf(String(active.id))
    const newIndex = ranked.indexOf(String(over.id))
    if (oldIndex === -1 || newIndex === -1) return

    onConfigChanged({
      ...datasourceConfig,
      rankedDatasources: arrayMove([...ranked], oldIndex, newIndex)
    })
  }

  const getDisplayName = (ds: string) => datasources.nameTitleMap.get(ds) || ds
  const activeDisplayName = activeId ? getDisplayName(activeId) : ''

  return (
    <Box>
      {/* Curated Datasources Section */}
      <Box sx={{ mb: 4 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button size="small" color="success" onClick={selectAll} sx={{ textTransform: 'none' }}>
              Select all
            </Button>
            <Button size="small" color="success" onClick={selectNone} sx={{ textTransform: 'none' }}>
              Select none
            </Button>
          </Box>
        </Box>

        {/* Active (ranked) datasources - sortable */}
        {ranked.length > 0 && (
          <Box sx={{ mb: 2 }}>
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragStart={handleDragStart}
              onDragEnd={handleDragEnd}
            >
              <SortableContext items={ranked} strategy={horizontalListSortingStrategy}>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5 }}>
                  {ranked.map((ds, index) => (
                    <SortableDatasourceChip
                      key={ds}
                      id={ds}
                      index={index}
                      displayName={getDisplayName(ds)}
                      onToggle={() => toggleDatasource(ds)}
                    />
                  ))}
                </Box>
              </SortableContext>
              <DragOverlay>
                {activeId ? (
                  <Chip
                    icon={<DragIndicatorIcon sx={{ fontSize: '1.1rem', opacity: 0.7 }} />}
                    label={`${ranked.indexOf(activeId) + 1}. ${activeDisplayName}`}
                    color="success"
                    sx={{ fontSize: '0.95rem', py: 0.5, boxShadow: 3, cursor: 'grabbing' }}
                  />
                ) : null}
              </DragOverlay>
            </DndContext>
          </Box>
        )}

        {/* Excluded datasources */}
        {datasourceConfig.excludedDatasources.length > 0 && (
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mb: 2 }}>
            {datasourceConfig.excludedDatasources.map(ds => (
              <Chip
                key={ds}
                label={getDisplayName(ds)}
                onClick={() => toggleDatasource(ds)}
                variant="outlined"
                color="default"
                sx={{ fontSize: '0.95rem', py: 0.5, opacity: 0.6 }}
              />
            ))}
          </Box>
        )}

        <Typography variant="body2" color="text.secondary">
          Click excluded databases to re-add • Drag to set priority order
        </Typography>
      </Box>

      <Divider sx={{ my: 3 }} />

      {/* Ontologies Section */}
      <Box>
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

