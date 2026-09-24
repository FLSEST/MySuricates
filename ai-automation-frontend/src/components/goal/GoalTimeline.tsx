import { useCallback, useMemo, useRef, useState } from 'react';
import {
  DndContext, DragOverlay, PointerSensor, pointerWithin, useSensor, useSensors,
  type Announcements, type DragEndEvent, type DragMoveEvent, type Modifier, type UniqueIdentifier,
} from '@dnd-kit/core';
import { getEventCoordinates } from '@dnd-kit/utilities';
import { Plus } from 'lucide-react';
import { Button, Card } from '../ui';
import { Hint } from '../hints/Hint';
import { useT } from '../../i18n';
import { useElementWidth } from '../../hooks/useElementWidth';
import { usePlanGoalTodo } from '../../hooks/useGoalTimeline';
import {
  CHIP_ANCHOR, CHIP_WIDTH, FALLBACK_WIDTH, axisFor, buildRows, dayAtX, dayOf, dropTargetOf, isOverdue, planChanges,
  xOfDay, type PlanChanges,
} from './timelineLayout';
import { ChipFace } from './TimelineChip';
import { TimelineAxisView } from './TimelineAxisView';
import { LABEL_COLUMN, TimelineLines, TimelineRowView, UnplannedBar } from './TimelineRows';
import { MilestoneCard, MilestoneForm } from './MilestonePopovers';
import type { Goal, GoalMilestone, GoalTodoItem } from '../../types';

/** Was gerade am Zeitstrahl offen ist: ein neuer Meilenstein (an einem Tag oder vom Knopf) oder einer. */
type MilestonePanel =
  | { kind: 'create'; day: number | null; x: number | null }
  | { kind: 'view' | 'edit'; id: string };

/** Breite der Meilenstein-Karten (`w-64`) – so weit dürfen sie nach links und rechts reichen. */
const PANEL_WIDTH = 256;
/** Höhe der Achse (`h-16`): Karten öffnen sich darunter. */
const AXIS_HEIGHT = 64;

/** Beim Ziehen sitzt der Statuspunkt unter dem Mauszeiger – dort, wo das Datum landet. */
const dotToCursor: Modifier = ({ activatorEvent, draggingNodeRect, transform }) => {
  const start = activatorEvent ? getEventCoordinates(activatorEvent) : null;
  if (!start || !draggingNodeRect) return transform;
  return {
    ...transform,
    x: transform.x + start.x - (draggingNodeRect.left + CHIP_ANCHOR),
    y: transform.y + start.y - (draggingNodeRect.top + draggingNodeRect.height / 2),
  };
};

interface GoalTimelineProps {
  goal: Goal;
  todos: GoalTodoItem[];
  milestones: GoalMilestone[];
  onOpenTodo: (todoId: string) => void;
  /** Für Tests: der Tag, der als „heute“ gilt. */
  now?: Date;
}

/**
 * Zeitstrahl eines Ziels (TODO e192df18): oben die Achse mit heute, den Meilensteinen und dem
 * Zieldatum, darunter je Meilenstein eine Zeile mit seinen fälligen TODOs, dann „ohne Meilenstein“
 * und die Leiste „ungeplant“. Ziehen setzt Fälligkeit und Meilenstein, das Menü jedes Chips kann
 * dasselbe ohne Ziehen.
 */
export function GoalTimeline({ goal, todos, milestones, onOpenTodo, now }: GoalTimelineProps) {
  const t = useT();
  const tt = t.goals.timeline;
  const [today] = useState(() => dayOf(now ?? new Date()));
  const measureRef = useRef<HTMLDivElement>(null);
  const addButtonRef = useRef<HTMLButtonElement>(null);
  const measured = useElementWidth(measureRef);
  const width = measured > 0 ? measured : FALLBACK_WIDTH;
  const targetDay = goal.targetDate ? dayOf(goal.targetDate) : null;
  const axis = useMemo(
    () => axisFor({ todos, milestones, targetDate: goal.targetDate, today }, width),
    [todos, milestones, goal.targetDate, today, width],
  );
  const { rows, unplanned } = useMemo(() => buildRows(todos, milestones, axis, width), [todos, milestones, axis, width]);
  const byId = useMemo(() => new Map(todos.map((todo) => [todo.id, todo])), [todos]);
  const { mutate: plan } = usePlanGoalTodo(goal.id);
  const [dragging, setDragging] = useState<GoalTodoItem | null>(null);
  const [preview, setPreview] = useState<{ row: string; day: number } | null>(null);
  const [panel, setPanel] = useState<MilestonePanel | null>(null);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }));
  const closePanel = useCallback(() => setPanel(null), []);
  const planFromMenu = useCallback(
    (todoId: string, changes: PlanChanges) => plan({ todoId, changes, announce: true }),
    [plan],
  );

  const todoOf = (id: UniqueIdentifier) => byId.get(String(id).replace(/^todo:/, ''));
  /** Der Tag unter dem Mauszeiger in der Zeile, über der er steht – eingerastet auf ganze Tage. */
  const dropDay = (event: DragMoveEvent | DragEndEvent): number | null => {
    const start = getEventCoordinates(event.activatorEvent);
    if (!start || !event.over) return null;
    return dayAtX(axis, start.x + event.delta.x - event.over.rect.left, width);
  };
  const rowName = (id: UniqueIdentifier) => {
    const target = dropTargetOf(String(id));
    if (!target) return '';
    if (target.kind === 'unplanned') return tt.unplanned;
    return milestones.find((m) => m.id === target.milestoneId)?.title ?? tt.noMilestone;
  };

  const handleDragMove = (event: DragMoveEvent) => {
    const target = event.over ? dropTargetOf(String(event.over.id)) : null;
    const day = target?.kind === 'row' ? dropDay(event) : null;
    const next = day === null || !event.over ? null : { row: String(event.over.id), day };
    setPreview((current) => (current?.row === next?.row && current?.day === next?.day ? current : next));
  };

  const handleDragEnd = (event: DragEndEvent) => {
    setDragging(null);
    setPreview(null);
    const todo = todoOf(event.active.id);
    const target = event.over ? dropTargetOf(String(event.over.id)) : null;
    if (!todo || !target) return;
    const day = target.kind === 'row' ? dropDay(event) : null;
    if (target.kind === 'row' && day === null) return;
    const changes = planChanges(todo, target, day);
    if (Object.keys(changes).length > 0) plan({ todoId: todo.id, changes });
  };

  const announcements: Announcements = {
    onDragStart: ({ active }) => tt.announce.start.replace('{title}', todoOf(active.id)?.title ?? ''),
    onDragOver: ({ over }) => (over ? tt.announce.over.replace('{row}', rowName(over.id)) : tt.announce.outside),
    onDragEnd: ({ active }) => tt.announce.end.replace('{title}', todoOf(active.id)?.title ?? ''),
    onDragCancel: () => tt.announce.cancel,
  };

  const panelAt = (x: number) => ({ left: Math.max(0, Math.min(x - PANEL_WIDTH / 2, width - PANEL_WIDTH)), top: AXIS_HEIGHT });
  const openMilestone = panel && panel.kind !== 'create' ? milestones.find((m) => m.id === panel.id) : undefined;
  const openMilestoneAt = openMilestone ? panelAt(xOfDay(axis, dayOf(openMilestone.dueDate), width)) : undefined;

  return (
    <section aria-label={tt.label} data-testid="goal-timeline" className="space-y-3">
      <Hint id="goal-timeline" />
      <div className="flex justify-end">
        <div className="relative">
          <Button ref={addButtonRef} variant="secondary" size="sm" icon={<Plus className="h-3.5 w-3.5" />}
            onClick={() => setPanel({ kind: 'create', day: null, x: null })}>
            {tt.addMilestone}
          </Button>
          {panel?.kind === 'create' && panel.x === null && (
            <MilestoneForm goalId={goal.id} initialDay={null} onClose={closePanel} triggerRef={addButtonRef} className="right-0 top-full mt-1" />
          )}
        </div>
      </div>

      <DndContext
        sensors={sensors}
        collisionDetection={pointerWithin}
        accessibility={{ announcements, screenReaderInstructions: { draggable: tt.dragHelp } }}
        onDragStart={(event) => {
          setDragging(todoOf(event.active.id) ?? null);
          setPanel(null);
        }}
        onDragMove={handleDragMove}
        onDragEnd={handleDragEnd}
        onDragCancel={() => {
          setDragging(null);
          setPreview(null);
        }}
      >
        <Card padding={false} className="p-3">
          <div className="relative">
            <TimelineLines axis={axis} width={width} targetDay={targetDay} preview={preview?.day ?? null} />
            <div className="flex">
              <div className={LABEL_COLUMN} />
              <div className="relative min-w-0 flex-1">
                <TimelineAxisView axis={axis} width={width} milestones={milestones} targetDay={targetDay}
                  preview={preview?.day ?? null} measureRef={measureRef}
                  onCreateAt={(day, x) => setPanel({ kind: 'create', day, x })}
                  onOpenMilestone={(id) => setPanel({ kind: 'view', id })} />
                {panel?.kind === 'create' && panel.x !== null && (
                  <MilestoneForm goalId={goal.id} initialDay={panel.day} onClose={closePanel} style={panelAt(panel.x)} />
                )}
                {openMilestone && panel?.kind === 'view' && (
                  <MilestoneCard goalId={goal.id} milestone={openMilestone} todos={todos} style={openMilestoneAt}
                    onEdit={() => setPanel({ kind: 'edit', id: openMilestone.id })} onClose={closePanel} />
                )}
                {openMilestone && panel?.kind === 'edit' && (
                  <MilestoneForm goalId={goal.id} milestone={openMilestone} initialDay={null} onClose={closePanel} style={openMilestoneAt} />
                )}
              </div>
            </div>
            {rows.map((row) => (
              <TimelineRowView key={row.key} row={row} axis={axis} width={width} today={today} milestones={milestones}
                onPlan={planFromMenu} onOpenTodo={onOpenTodo} onOpenMilestone={(id) => setPanel({ kind: 'view', id })} />
            ))}
          </div>
        </Card>
        <UnplannedBar todos={unplanned} milestones={milestones} today={today} onPlan={planFromMenu} onOpenTodo={onOpenTodo} />
        <DragOverlay modifiers={[dotToCursor]} dropAnimation={null}>
          {dragging && (
            <div style={{ width: CHIP_WIDTH }}>
              <ChipFace todo={dragging} overdue={isOverdue(dragging, today)} lifted />
            </div>
          )}
        </DragOverlay>
      </DndContext>
    </section>
  );
}
