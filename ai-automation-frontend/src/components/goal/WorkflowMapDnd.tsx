import { useCallback, useMemo, useState, type ReactNode } from 'react';
import {
  DndContext, DragOverlay, KeyboardSensor, MeasuringStrategy, PointerSensor, useSensor, useSensors,
  type Announcements, type DragEndEvent, type DragOverEvent, type DragStartEvent,
} from '@dnd-kit/core';
import { Workflow } from 'lucide-react';
import { ConfirmModal } from '../ui';
import { useT } from '../../i18n';
import { useToast } from '../../hooks/useToast';
import { useAttachDroppedWorkflow, useLinkTodoWorkflow } from '../../hooks/useGoalWorkflowMap';
import { useGoalWorkflowsScope } from './goalWorkflowsContext';
import {
  liveLine, parseTarget, targetCycleCoordinates, verdict, type DraggedWorkflow, type MapContext, type MapTarget,
} from './workflowMap';
import { WorkflowMapDragContext, besideCursor, workflowMapCollision, type WorkflowMapDragValue } from './workflowMapDrag';
import type { GoalRelations, GoalWorkPackage } from '../../types';

const KEYBOARD_CODES = { start: ['Space'], cancel: ['Escape'], end: ['Enter', 'Space'] };
const OVERLAY_MODIFIERS = [besideCursor];

interface Replacing {
  todoId: string;
  todoTitle: string;
  workflow: DraggedWorkflow;
  previous: { id: string; name: string };
}

/**
 * Drag and drop of the workflow assignment (52bc1e8c): wraps card and workflow bar. A
 * workflow on a TODO processes it (an existing fix workflow is replaced only after asking),
 * on a package or the header row of the goal it is assigned there. Below the
 * dragged workflow it says where it takes effect; every assignment can be undone in the toast.
 */
export function WorkflowMapDnd({ goalId, goalTitle, relations, workPackages, children }: {
  goalId: string;
  goalTitle: string;
  relations: GoalRelations | undefined;
  workPackages: GoalWorkPackage[];
  children: ReactNode;
}) {
  const t = useT();
  const tm = t.goals.workflowMap;
  const toast = useToast();
  const workflows = useGoalWorkflowsScope();
  const link = useLinkTodoWorkflow(goalId);
  const attach = useAttachDroppedWorkflow(goalId);
  const [dragged, setDragged] = useState<DraggedWorkflow | null>(null);
  const [overId, setOverId] = useState<string | null>(null);
  const [replacing, setReplacing] = useState<Replacing | null>(null);
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { keyboardCodes: KEYBOARD_CODES, coordinateGetter: targetCycleCoordinates }),
  );

  const context = useMemo<MapContext>(() => ({
    goalTitle,
    todos: new Map((relations?.todos ?? []).map((todo) => [todo.id, {
      title: todo.title, projectId: todo.projectId, projectName: todo.projectName, candidate: todo.candidate,
      linkedWorkflowId: todo.workers.find((worker) => worker.kind === 'LINKED')?.workflowId ?? null,
    }])),
    packages: new Map(workPackages.map((item) => [item.id, { title: item.title }])),
    attachSupported: workflows?.supported ?? false,
    attachments: workflows?.attachments ?? [],
  }), [goalTitle, relations, workPackages, workflows?.supported, workflows?.attachments]);

  const judge = useCallback((targetId: string) => {
    const target = parseTarget(targetId);
    return dragged && target ? verdict(dragged, target, context) : null;
  }, [dragged, context]);

  const liveTexts = tm.live;
  const line = dragged ? liveLine(dragged, parseTarget(overId), context, liveTexts) : null;

  const drop = (workflow: DraggedWorkflow, target: MapTarget) => {
    const result = verdict(workflow, target, context);
    if (result === 'same') {
      toast.info(tm.already);
      return;
    }
    if (result === 'refused') return;
    if (target.kind === 'todo') {
      const todo = relations?.todos.find((item) => item.id === target.todoId);
      if (!todo) return;
      const linked = todo.workers.find((worker) => worker.kind === 'LINKED');
      const previous = linked ? { id: linked.workflowId, name: linked.workflowName } : null;
      if (previous) {
        setReplacing({ todoId: todo.id, todoTitle: todo.title, workflow, previous });
        return;
      }
      link.mutate({ todoId: todo.id, todoTitle: todo.title, workflow: { id: workflow.id, name: workflow.name }, previous: null });
      return;
    }
    const workPackageId = target.kind === 'package' ? target.workPackageId : null;
    const message = workPackageId
      ? tm.attachedPackage.replace('{workflow}', workflow.name).replace('{title}', context.packages.get(workPackageId)?.title ?? '')
      : tm.attachedGoal.replace('{workflow}', workflow.name);
    attach.mutate({ workflowId: workflow.id, workPackageId, message });
  };

  const draggedOf = (event: DragStartEvent | DragEndEvent) =>
    (event.active.data.current as { workflow?: DraggedWorkflow } | undefined)?.workflow ?? null;
  const onDragStart = (event: DragStartEvent) => {
    setDragged(draggedOf(event));
    setOverId(null);
  };
  const onDragOver = (event: DragOverEvent) => setOverId(event.over ? String(event.over.id) : null);
  const reset = () => {
    setDragged(null);
    setOverId(null);
  };
  const onDragEnd = (event: DragEndEvent) => {
    const workflow = draggedOf(event);
    const target = parseTarget(event.over?.id);
    reset();
    if (workflow && target) drop(workflow, target);
  };

  const nameOf = (id: string | number) => String(id).startsWith('wm-wf:') ? (dragged?.name ?? '') : '';
  const announcements: Announcements = {
    onDragStart: ({ active }) => tm.announce.start.replace('{name}', nameOf(active.id)),
    onDragOver: ({ over }) => (dragged ? liveLine(dragged, parseTarget(over?.id), context, liveTexts).text : undefined),
    onDragEnd: ({ active }) => tm.announce.end.replace('{name}', nameOf(active.id)),
    onDragCancel: () => tm.announce.cancel,
  };

  const value = useMemo<WorkflowMapDragValue>(() => ({ dragged, overId, context, judge }), [dragged, overId, context, judge]);

  return (
    <WorkflowMapDragContext.Provider value={value}>
      <DndContext sensors={sensors} collisionDetection={workflowMapCollision}
        measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
        accessibility={{ announcements, screenReaderInstructions: { draggable: tm.dragHelp } }}
        onDragStart={onDragStart} onDragOver={onDragOver} onDragEnd={onDragEnd} onDragCancel={reset}>
        {children}
        <DragOverlay dropAnimation={null} modifiers={OVERLAY_MODIFIERS}>
          {dragged && (
            <div className="w-64" data-testid="workflow-map-overlay">
              <div className="flex items-center gap-2 rounded-md border border-accent bg-surface-raised px-2.5 py-1.5 text-xs shadow-lg ring-1 ring-accent">
                <Workflow className="h-3.5 w-3.5 shrink-0 text-accent" aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate font-medium text-text">{dragged.name}</span>
              </div>
              {line && (
                <p data-testid="workflow-map-live" data-refused={line.refused || undefined}
                  className={`mt-1 inline-block max-w-full truncate rounded px-1.5 py-0.5 text-[11px] font-medium shadow ${
                    line.refused ? 'bg-danger-subtle text-danger' : 'bg-accent text-text-inverse'}`}>
                  {line.text}
                </p>
              )}
            </div>
          )}
        </DragOverlay>
      </DndContext>
      <ConfirmModal
        open={replacing !== null}
        onClose={() => setReplacing(null)}
        onConfirm={() => {
          if (!replacing) return;
          link.mutate({
            todoId: replacing.todoId, todoTitle: replacing.todoTitle,
            workflow: { id: replacing.workflow.id, name: replacing.workflow.name }, previous: replacing.previous,
          });
          setReplacing(null);
        }}
        title={tm.replaceTitle}
        message={replacing ? tm.replaceMessage.replace('{todo}', replacing.todoTitle)
          .replace('{current}', replacing.previous.name).replace('{next}', replacing.workflow.name) : null}
        confirmLabel={tm.replaceConfirm}
        isPending={link.isPending}
      />
    </WorkflowMapDragContext.Provider>
  );
}
