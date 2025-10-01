import * as React from "react";
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type DragStartEvent,
  type DragOverEvent,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Box, Paper, Typography } from "@mui/material";
import { JSX } from "react";

const LIST_WIDTH = 190;
const LIST_HEIGHT = 300;
const GRID = 8;

export interface ListEntry {
  id: string;
  content: JSX.Element;
}

export interface List {
  title: string;
  entries: ListEntry[];
}

interface Props {
  lists: List[];
  onChange: (lists: List[]) => void;
}

/** Utilities */
function deepCloneLists(lists: List[]): List[] {
  return lists.map((l) => ({ title: l.title, entries: [...l.entries] }));
}

function findListIndexByItemId(lists: List[], itemId: string): number {
  return lists.findIndex((l) => l.entries.some((e) => e.id === itemId));
}

function findItemIndex(l: List, itemId: string): number {
  return l.entries.findIndex((e) => e.id === itemId);
}

function getListId(idx: number) {
  return `list-${idx}`;
}

function parseListIndex(listId: string) {
  return Number(listId.split("-")[1]);
}

/** Sortable item (a single draggable row) */
function SortableItem({
  id,
  content,
}: {
  id: string;
  content: JSX.Element;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id });

  const style: React.CSSProperties = {
    userSelect: "none",
    padding: GRID,
    marginBottom: GRID,
    background: isDragging ? "#ccc" : "#ddd",
    transform: CSS.Transform.toString(transform),
    transition,
    borderRadius: 6,
    boxShadow: isDragging ? "0 4px 12px rgba(0,0,0,0.15)" : undefined,
  };

  return (
    <div ref={setNodeRef} style={style} {...attributes} {...listeners}>
      {content}
    </div>
  );
}

/** The main component */
export default function DragAndDropLists({ lists, onChange }: Props) {
  const [internal, setInternal] = React.useState<List[]>(() => deepCloneLists(lists));
  const [activeId, setActiveId] = React.useState<string | null>(null);

  // keep internal in sync when parent updates lists
  React.useEffect(() => setInternal(deepCloneLists(lists)), [lists]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } })
  );

  const activeItem =
    activeId &&
    internal.flatMap((l) => l.entries).find((e) => e.id === activeId) || null;

  const handleDragStart = (event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  };

  // Allow “hover to move” across lists for a nicer cross-column UX.
  const handleDragOver = (event: DragOverEvent) => {
    const { active, over } = event;
    if (!over) return;

    const activeItemId = String(active.id);
    const overId = String(over.id);

    // If hovering a list container, over.id will be the list id (e.g., "list-1").
    const overIsList = overId.startsWith("list-");
    if (!overIsList) return;

    const sourceListIdx = findListIndexByItemId(internal, activeItemId);
    const destListIdx = parseListIndex(overId);
    if (sourceListIdx === -1 || destListIdx === -1 || sourceListIdx === destListIdx) return;

    setInternal((prev) => {
      const next = deepCloneLists(prev);
      const src = next[sourceListIdx];
      const dst = next[destListIdx];
      const fromIdx = findItemIndex(src, activeItemId);
      if (fromIdx === -1) return prev;

      // Move into destination list end while hovering its container
      const [moved] = src.entries.splice(fromIdx, 1);
      dst.entries.splice(dst.entries.length, 0, moved);
      return next;
    });
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    setActiveId(null);
    if (!over) {
      // dropped outside everything → revert internal to props
      setInternal(deepCloneLists(lists));
      return;
    }

    const activeItemId = String(active.id);
    const overId = String(over.id);

    const sourceListIdx = findListIndexByItemId(internal, activeItemId);

    // If dropped over an item, we'll sort within that item's list; if dropped over a list, append to end.
    let destListIdx: number;
    let destIndex: number;

    if (overId.startsWith("list-")) {
      destListIdx = parseListIndex(overId);
      destIndex = internal[destListIdx].entries.length; // append to end
    } else {
      destListIdx = findListIndexByItemId(internal, overId);
      const overItemIdx = findItemIndex(internal[destListIdx], overId);
      destIndex = overItemIdx === -1 ? internal[destListIdx].entries.length : overItemIdx;
    }

    if (sourceListIdx === -1 || destListIdx === -1) {
      setInternal(deepCloneLists(lists));
      return;
    }

    setInternal((prev) => {
      const next = deepCloneLists(prev);
      const sourceList = next[sourceListIdx];
      const destList = next[destListIdx];

      const fromIdx = findItemIndex(sourceList, activeItemId);
      if (fromIdx === -1) return prev;

      const [moved] = sourceList.entries.splice(fromIdx, 1);

      if (sourceListIdx === destListIdx) {
        // Reorder inside same list
        destList.entries = arrayMove(destList.entries, fromIdx, destIndex);
      } else {
        // Insert into another list at computed index
        destList.entries.splice(destIndex, 0, moved);
      }

      // Emit up
      onChange(next);
      return next;
    });
  };

  return (
    <DndContext
      sensors={sensors}
      onDragStart={handleDragStart}
      onDragOver={handleDragOver}
      onDragEnd={handleDragEnd}
    >
      <Box sx={{ display: "flex", gap: 2 }}>
        {internal.map((list, i) => {
          const listId = getListId(i);
          return (
            <Paper
              key={listId}
              variant="outlined"
              sx={{
                width: LIST_WIDTH,
                p: 1,
                display: "inline-block",
                verticalAlign: "top",
                borderRadius: 2,
              }}
            >
              <Typography variant="subtitle1" sx={{ px: 1, pt: 1, pb: 0.5 }}>
                {list.title}
              </Typography>

              {/* Sortable context: provide the list's item ids */}
              <Box
                id={listId}
                sx={{
                  background: "white",
                  p: 1,
                  height: LIST_HEIGHT,
                  overflowY: "auto",
                  outline: "2px solid transparent",
                  "&:hover": { outlineColor: "#ccffcc" },
                  borderRadius: 2,
                }}
              >
                <SortableContext
                  items={list.entries.map((e) => e.id)}
                  strategy={verticalListSortingStrategy}
                >
                  {/* Make the whole list droppable by giving the container an id (handled via DndContext) */}
                  <DroppableListBoundary id={listId} />
                  {list.entries.map((item) => (
                    <SortableItem key={item.id} id={item.id} content={item.content} />
                  ))}
                </SortableContext>
              </Box>
            </Paper>
          );
        })}
      </Box>

      {/* Nice drag preview */}
      <DragOverlay>
        {activeItem ? (
          <div
            style={{
              padding: GRID,
              background: "#ccc",
              borderRadius: 6,
              boxShadow: "0 6px 18px rgba(0,0,0,0.2)",
              width: LIST_WIDTH - GRID * 2,
            }}
          >
            {activeItem.content}
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}

/**
 * Minimal invisible droppable “boundary” so hovering a list
 * is recognized as a distinct drop target (id = list-x).
 */
function DroppableListBoundary({ id }: { id: string }) {
  // useSortable also works for containers (it registers a drop target with this id)
  const { setNodeRef } = useSortable({ id });
  return <div ref={setNodeRef} style={{ position: "absolute", inset: 0, pointerEvents: "none" }} />;
}
