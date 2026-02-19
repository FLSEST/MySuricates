import { useState } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useToast } from '../../hooks/useToast';
import { useT } from '../../i18n';
import { SortableBlock } from './SortableBlock';
import type { BlockDefinition } from '../../types';

interface SortableBlockListProps {
  workflowId: string;
  blocks: BlockDefinition[];
  onEditBlock: (index: number) => void;
  onRemoveBlock: (index: number) => void;
}

export function SortableBlockList({ workflowId, blocks, onEditBlock, onRemoveBlock }: SortableBlockListProps) {
  const updateWorkflow = useUpdateWorkflow();
  const toast = useToast();
  const t = useT();
  const [optimisticBlocks, setOptimisticBlocks] = useState<BlockDefinition[] | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const displayBlocks = optimisticBlocks ?? blocks;
  const items = displayBlocks.map((_, i) => `block-${i}`);

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const oldIndex = items.indexOf(active.id as string);
    const newIndex = items.indexOf(over.id as string);

    const reordered = [...displayBlocks];
    const [moved] = reordered.splice(oldIndex, 1);
    reordered.splice(newIndex, 0, moved!);

    // Optimistic update
    setOptimisticBlocks(reordered);

    updateWorkflow.mutate(
      { id: workflowId, data: { blocks: reordered } },
      {
        onSuccess: () => {
          setOptimisticBlocks(null);
          toast.success(t.toast.blocksReordered);
        },
        onError: () => {
          setOptimisticBlocks(null);
          toast.error(t.toast.reorderFailed);
        },
      }
    );
  };

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
      <SortableContext items={items} strategy={verticalListSortingStrategy}>
        <div className="divide-y divide-border">
          {displayBlocks.map((block, idx) => (
            <SortableBlock
              key={`block-${idx}`}
              id={`block-${idx}`}
              index={idx}
              block={block}
              onEdit={onEditBlock}
              onRemove={onRemoveBlock}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}
