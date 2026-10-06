import { type ReactNode, type Ref } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { COLUMNS, NODE_HEIGHT, type MapGroup, type MapNode } from './goalRelationLayout';
import { GOAL_TARGET_ID, packageTargetId, todoTargetId } from './workflowMap';
import { dropToneOf, useWorkflowMapDrag } from './workflowMapDrag';

/**
 * Drop surfaces of the workflow assignment (52bc1e8c) in the card: every TODO and every group of a
 * work package. While dragging only the matching ones light up; without a drag in progress none of it is visible.
 */

/** A TODO as a drop surface - wraps its node. */
export function TodoDropTarget({ node, children }: { node: MapNode; children: ReactNode }) {
  const drag = useWorkflowMapDrag();
  const id = todoTargetId(node.todoIds[0]!);
  const { setNodeRef, isOver } = useDroppable({ id, disabled: !drag });
  const tone = dropToneOf(drag?.judge(id) ?? null, isOver);
  return (
    <g ref={setNodeRef as unknown as Ref<SVGGElement>} data-drop-target={id} data-accepts={tone ? String(drag?.judge(id) === 'ok') : undefined}
      opacity={tone && tone.opacity < 1 ? tone.opacity : undefined}>
      {children}
      {tone && tone.opacity === 1 && (
        <rect x={node.x - 3} y={node.y - 3} width={node.w + 6} height={NODE_HEIGHT + 6} rx={10}
          style={{ fill: 'none', stroke: tone.stroke }} strokeWidth={isOver ? 2.5 : 1.5}
          strokeDasharray={isOver ? undefined : '4 3'} pointerEvents="none" />
      )}
    </g>
  );
}

/**
 * The group of a work package (header row with its rows) as a drop surface. If the backend knows no
 * workflows at the goal (404), they do not exist at all - like the goal band; then only the TODOs remain.
 */
export function PackageDropTarget({ group, workPackageId }: { group: MapGroup; workPackageId: string }) {
  const drag = useWorkflowMapDrag();
  const id = packageTargetId(workPackageId);
  const supported = drag?.context.attachSupported ?? false;
  const { setNodeRef, isOver } = useDroppable({ id, disabled: !supported });
  if (!supported) return null;
  const tone = dropToneOf(drag?.judge(id) ?? null, isOver);
  return (
    <rect ref={setNodeRef as unknown as Ref<SVGRectElement>} data-drop-target={id}
      data-accepts={tone ? String(drag?.judge(id) === 'ok') : undefined}
      x={COLUMNS[1].x - 8} y={group.y} width={COLUMNS[1].w + 16} height={Math.max(group.h ?? 0, 24)} rx={10}
      style={{ fill: tone && tone.opacity === 1 ? tone.fill : 'transparent', stroke: tone && tone.opacity === 1 ? tone.stroke : 'transparent' }}
      strokeWidth={isOver ? 2 : 1} strokeDasharray={isOver ? undefined : '4 3'} pointerEvents="none" />
  );
}

/** The header row „Ganzes Ziel“ above the card: a workflow dropped here is assigned to the whole goal. */
export function GoalDropTarget({ label, hint }: { label: string; hint: string }) {
  const drag = useWorkflowMapDrag();
  const { setNodeRef, isOver } = useDroppable({ id: GOAL_TARGET_ID, disabled: !drag?.context.attachSupported });
  if (!drag || !drag.context.attachSupported) return null;
  const verdict = drag.judge(GOAL_TARGET_ID);
  const active = verdict === 'ok';
  return (
    <div ref={setNodeRef} data-drop-target={GOAL_TARGET_ID} data-accepts={verdict === null ? undefined : String(active)} title={hint}
      className={`rounded-lg border px-3 py-2 text-sm transition-colors ${
        verdict === null ? 'border-dashed border-border text-text-tertiary'
          : !active ? 'border-dashed border-border text-text-tertiary opacity-50'
            : isOver ? 'border-accent bg-accent-subtle text-accent' : 'border-dashed border-accent text-accent'}`}>
      {label}
    </div>
  );
}
