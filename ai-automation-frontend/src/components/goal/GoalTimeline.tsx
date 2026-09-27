import { useCallback, useMemo, useRef, useState } from 'react';
import {
  DndContext, DragOverlay, KeyboardSensor, PointerSensor, useSensor, useSensors,
  type Announcements, type DragEndEvent, type DragMoveEvent, type Modifier, type UniqueIdentifier,
} from '@dnd-kit/core';
import { getEventCoordinates } from '@dnd-kit/utilities';
import { Plus, Trash2 } from 'lucide-react';
import { Button, Card, ConfirmModal } from '../ui';
import { Hint } from '../hints/Hint';
import { useT } from '../../i18n';
import { useElementWidth } from '../../hooks/useElementWidth';
import { useCreateMilestone, useDeleteMilestone, useMoveMilestone, usePlanGoalTodo } from '../../hooks/useGoalTimeline';
import {
  CHIP_ANCHOR, CHIP_WIDTH, DIAMOND_ANCHOR, FALLBACK_WIDTH, axisFor, buildBands, dayAtX, dayOf, dropTargetOf, dueDateOfDay,
  isDated, isOverdue, milestoneAccepts, milestoneDateChange, planChanges, todoAccepts, type DropTarget, type PlanChanges,
} from './timelineLayout';
import { ChipFace } from './TimelineChip';
import { TimelineAxisView } from './TimelineAxisView';
import { LABEL_COLUMN, TimelineLines, UnplannedBar } from './TimelineRows';
import { TimelineBandView, type InlineAt, type PanelSlot } from './TimelineBand';
import { MilestoneCard, MilestoneForm } from './MilestonePopovers';
import { MilestoneDiamond } from './MilestoneDiamond';
import { TIMELINE_KEYBOARD_CODES, chipDragData, timelineCollision, timelineKeyboardCoordinates } from './timelineKeyboard';
import { TimelineDragContext, type Dragged } from './timelineDrag';
import type { Goal, GoalMilestone, GoalProject, GoalTodoItem } from '../../types';

/** Was gerade am Zeitstrahl offen ist: ein neues Teilziel (an einem Tag der Achse oder vom Knopf) oder eins. */
type MilestonePanel =
  | { kind: 'create'; day: number | null; x: number | null }
  | { kind: 'view' | 'edit'; id: string };

/** Breite der Teilziel-Karten (`w-64`) – so weit dürfen sie nach links und rechts reichen. */
const PANEL_WIDTH = 256;
/** Höhe der Achse (`h-16`): Karten der Achse öffnen sich darunter. */
const AXIS_HEIGHT = 64;
/** Höhe der Kopfzeile eines Bands (`h-9`): Karten einer Raute öffnen sich darunter. */
const STRIP_HEIGHT = 36;

/**
 * Beim Ziehen mit der Maus sitzt der Punkt, der das Datum trägt, unter dem Mauszeiger – bei einem
 * Chip sein Statuspunkt, bei einem Teilziel die Raute. Per Tastatur gibt es keinen Zeiger: das Bild
 * beginnt genau über dem Gezogenen.
 */
const dotToCursor: Modifier = ({ activatorEvent, active, draggingNodeRect, transform }) => {
  const start = activatorEvent ? getEventCoordinates(activatorEvent) : null;
  if (!start || !draggingNodeRect) return transform;
  const data = chipDragData(active);
  const faceAnchor = data.kind === 'milestone' ? DIAMOND_ANCHOR : data.flipped ? CHIP_WIDTH - CHIP_ANCHOR : CHIP_ANCHOR;
  return {
    ...transform,
    x: transform.x + start.x - (draggingNodeRect.left + faceAnchor),
    y: transform.y + start.y - (draggingNodeRect.top + draggingNodeRect.height / 2),
  };
};

interface GoalTimelineProps {
  goal: Goal;
  todos: GoalTodoItem[];
  milestones: GoalMilestone[];
  /** Die Projekte des Ziels – je eins ein Band. */
  projects: GoalProject[];
  onOpenTodo: (todoId: string) => void;
  /** Für Tests: der Tag, der als „heute“ gilt. */
  now?: Date;
}

/**
 * Zeitstrahl eines Ziels (TODO e192df18, Bänder c60bf914): oben die Achse mit heute und dem
 * Zieldatum, darunter je Projekt ein Band – Kopfzeile mit den Rauten der Teilziele, je Teilziel eine
 * Zeile mit seinen fälligen TODOs, „ohne Teilziel“ und die Leiste „ohne Datum“ –, zuletzt die Leiste
 * „ungeplant“. Ziehen setzt Fälligkeit, Teilziel oder das Datum eines Teilziels; TODOs landen nur in
 * Teilzielen ihres Projekts. Ein Klick in ein Band legt dort ein Teilziel an.
 */
export function GoalTimeline({ goal, todos, milestones, projects, onOpenTodo, now }: GoalTimelineProps) {
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
  const { bands, unplanned } = useMemo(
    () => buildBands(todos, milestones, projects.map(({ projectId, name }) => ({ projectId, name })), axis, width),
    [todos, milestones, projects, axis, width],
  );
  const dated = useMemo(() => milestones.filter(isDated), [milestones]);
  const todoById = useMemo(() => new Map(todos.map((todo) => [todo.id, todo])), [todos]);
  const milestoneById = useMemo(() => new Map(milestones.map((milestone) => [milestone.id, milestone])), [milestones]);
  const { mutate: plan } = usePlanGoalTodo(goal.id);
  const { mutate: moveMilestone } = useMoveMilestone(goal.id);
  const create = useCreateMilestone(goal.id);
  const [dragged, setDragged] = useState<Dragged>(null);
  // Das Bild unter dem Zeiger steht wie der Chip – bei einem gespiegelten Chip ist der Punkt rechts
  const [draggingFlipped, setDraggingFlipped] = useState(false);
  const [preview, setPreview] = useState<{ over: string; day: number } | null>(null);
  const [panel, setPanel] = useState<MilestonePanel | null>(null);
  const [inline, setInline] = useState<(InlineAt & { band: string }) | null>(null);
  // Sub-goal whose deletion waits for the confirmation (23635ef6)
  const [removing, setRemoving] = useState<GoalMilestone | null>(null);
  const removeMilestone = useDeleteMilestone(goal.id);
  const keyboardOptions = useMemo(
    () => ({ keyboardCodes: TIMELINE_KEYBOARD_CODES, coordinateGetter: timelineKeyboardCoordinates(axis, width) }),
    [axis, width],
  );
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, keyboardOptions),
  );
  const closePanel = useCallback(() => setPanel(null), []);
  const planFromMenu = useCallback(
    (todoId: string, changes: PlanChanges) => plan({ todoId, changes, announce: true }),
    [plan],
  );

  const draggedOf = (id: UniqueIdentifier): Dragged => {
    const [kind, key] = String(id).split(':');
    if (kind === 'ms') {
      const milestone = milestoneById.get(key ?? '');
      return milestone ? { kind: 'milestone', milestone } : null;
    }
    const todo = todoById.get(key ?? '');
    return todo ? { kind: 'todo', todo } : null;
  };
  const titleOf = (id: UniqueIdentifier) => {
    const item = draggedOf(id);
    return item?.kind === 'todo' ? item.todo.title : item?.kind === 'milestone' ? item.milestone.title : '';
  };
  /**
   * Der Tag unter dem Mauszeiger in der Fläche, über der er steht – eingerastet auf ganze Tage. Per
   * Tastatur (kein Zeiger) der Tag unter dem Punkt des Angehobenen.
   */
  const dropDay = (event: DragMoveEvent | DragEndEvent): number | null => {
    if (!event.over) return null;
    const start = getEventCoordinates(event.activatorEvent);
    const lifted = event.active.rect.current.translated;
    const x = start ? start.x + event.delta.x : lifted ? lifted.left + chipDragData(event.active).anchor : null;
    return x === null ? null : dayAtX(axis, x - event.over.rect.left, width);
  };
  const hasDays = (target: DropTarget | null) => target?.kind === 'row' || target?.kind === 'strip';
  const bandName = (band: string) => bands.find((b) => b.key === band)?.name ?? t.goals.outline.noProject;
  const targetName = (id: UniqueIdentifier) => {
    const target = dropTargetOf(String(id));
    if (!target) return '';
    switch (target.kind) {
      case 'unplanned': return tt.unplanned;
      case 'strip': return tt.band.replace('{project}', bandName(target.band));
      case 'tray': return `${tt.undated} · ${bandName(target.band)}`;
      default: return target.milestoneId
        ? milestoneById.get(target.milestoneId)?.title ?? tt.noMilestone
        : `${tt.noMilestone} · ${bandName(target.band)}`;
    }
  };

  const handleDragMove = (event: DragMoveEvent) => {
    const target = event.over ? dropTargetOf(String(event.over.id)) : null;
    const day = hasDays(target) ? dropDay(event) : null;
    const next = day === null || !event.over ? null : { over: String(event.over.id), day };
    setPreview((current) => (current?.over === next?.over && current?.day === next?.day ? current : next));
  };

  const handleDragEnd = (event: DragEndEvent) => {
    setDragged(null);
    setPreview(null);
    const item = draggedOf(event.active.id);
    const target = event.over ? dropTargetOf(String(event.over.id)) : null;
    if (!item || !target) return;
    const day = hasDays(target) ? dropDay(event) : null;
    if (hasDays(target) && day === null) return;
    if (item.kind === 'milestone') {
      if (!milestoneAccepts(item.milestone, target)) return;
      const dueDate = milestoneDateChange(item.milestone, target, day);
      if (dueDate !== undefined) moveMilestone({ id: item.milestone.id, dueDate });
      return;
    }
    if (!todoAccepts(item.todo, target)) return;
    const changes = planChanges(item.todo, target, day);
    if (Object.keys(changes).length > 0) plan({ todoId: item.todo.id, changes });
  };

  const announcements: Announcements = {
    onDragStart: ({ active }) => tt.announce.start.replace('{title}', titleOf(active.id)),
    onDragOver: ({ over }) => (over ? tt.announce.over.replace('{row}', targetName(over.id)) : tt.announce.outside),
    onDragEnd: ({ active }) => tt.announce.end.replace('{title}', titleOf(active.id)),
    onDragCancel: () => tt.announce.cancel,
  };

  const clampPanel = (x: number) => Math.max(0, Math.min(x - PANEL_WIDTH / 2, width - PANEL_WIDTH));
  const openPanel = (id: string) => {
    setInline(null);
    setPanel({ kind: 'view', id });
  };
  const panelFor = (milestone: GoalMilestone, slot: PanelSlot) => {
    if (!panel || panel.kind === 'create' || panel.id !== milestone.id) return null;
    const placement = slot.where === 'strip'
      ? { style: { left: clampPanel(slot.x), top: STRIP_HEIGHT } }
      : { className: 'left-0 top-full mt-1' };
    if (panel.kind === 'edit') {
      return <MilestoneForm goalId={goal.id} projects={projects} milestone={milestone} initialDay={null} onClose={closePanel} {...placement} />;
    }
    return (
      <MilestoneCard milestone={milestone} todos={todos} onClose={closePanel} {...placement}
        onEdit={() => setPanel({ kind: 'edit', id: milestone.id })}
        onDelete={() => {
          setPanel(null);
          setRemoving(milestone);
        }} />
    );
  };
  const createInline = (band: string, title: string, day: number | null) => create.mutate(
    { title, projectId: band, ...(day !== null ? { dueDate: dueDateOfDay(day) } : {}) },
    { onSuccess: () => setInline(null) },
  );

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
            <MilestoneForm goalId={goal.id} projects={projects} initialDay={null} onClose={closePanel} triggerRef={addButtonRef}
              className="right-0 top-full mt-1" />
          )}
        </div>
      </div>

      <DndContext
        sensors={sensors}
        collisionDetection={timelineCollision}
        accessibility={{ announcements, screenReaderInstructions: { draggable: tt.dragHelp } }}
        onDragStart={(event) => {
          setDragged(draggedOf(event.active.id));
          setDraggingFlipped(chipDragData(event.active).flipped);
          setPanel(null);
          setInline(null);
        }}
        onDragMove={handleDragMove}
        onDragEnd={handleDragEnd}
        onDragCancel={() => {
          setDragged(null);
          setPreview(null);
        }}
      >
        <TimelineDragContext.Provider value={dragged}>
          <Card padding={false} className="p-3">
            {/* Auf schmalen Bildschirmen scrollt der Zeitstrahl in sich, die Seite nicht */}
            <div className="max-sm:-mx-3 max-sm:overflow-x-auto max-sm:px-3">
              <div className="relative max-sm:min-w-[36rem]">
                <TimelineLines axis={axis} width={width} targetDay={targetDay} preview={preview?.day ?? null} />
                <div className="flex">
                  <div className={LABEL_COLUMN} />
                  <div className="relative min-w-0 flex-1">
                    <TimelineAxisView axis={axis} width={width} milestones={dated} targetDay={targetDay}
                      preview={preview?.day ?? null} measureRef={measureRef}
                      onCreateAt={(day, x) => {
                        setInline(null);
                        setPanel({ kind: 'create', day, x });
                      }} />
                    {panel?.kind === 'create' && panel.x !== null && (
                      <MilestoneForm goalId={goal.id} projects={projects} initialDay={panel.day} onClose={closePanel}
                        style={{ left: clampPanel(panel.x), top: AXIS_HEIGHT }} />
                    )}
                  </div>
                </div>
                {bands.length === 0 && (
                  <p className="border-t border-border px-1 py-4 text-sm text-text-secondary" data-testid="timeline-no-projects">
                    {tt.noProjects}
                  </p>
                )}
                {bands.map((band) => (
                  <TimelineBandView key={band.key} band={band} axis={axis} width={width} today={today} milestones={milestones}
                    inline={inline?.band === band.key ? inline : null} creating={create.isPending}
                    onInline={(at) => {
                      setPanel(null);
                      setInline(at ? { ...at, band: band.key } : null);
                    }}
                    onCreate={(title, day) => createInline(band.key, title, day)}
                    onPlan={planFromMenu} onOpenTodo={onOpenTodo} onOpenMilestone={openPanel} panelFor={panelFor} />
                ))}
              </div>
            </div>
          </Card>
          <UnplannedBar todos={unplanned} milestones={milestones} today={today} onPlan={planFromMenu} onOpenTodo={onOpenTodo} />
        </TimelineDragContext.Provider>
        <DragOverlay modifiers={[dotToCursor]} dropAnimation={null}>
          {dragged?.kind === 'todo' && (
            <div style={{ width: CHIP_WIDTH }}>
              <ChipFace todo={dragged.todo} overdue={isOverdue(dragged.todo, today)} flipped={draggingFlipped} lifted />
            </div>
          )}
          {dragged?.kind === 'milestone' && (
            <div className="flex h-5 items-center justify-center rounded bg-surface-raised shadow-lg ring-1 ring-accent"
              style={{ width: 2 * DIAMOND_ANCHOR }}>
              <MilestoneDiamond state={dragged.milestone.state} size="lg" />
            </div>
          )}
        </DragOverlay>
      </DndContext>

      <ConfirmModal
        open={removing !== null}
        onClose={() => setRemoving(null)}
        onConfirm={() => removing && removeMilestone.mutate(removing.id, { onSettled: () => setRemoving(null) })}
        title={t.goals.milestones.deleteTitle}
        message={removing ? t.goals.milestones.deleteConfirm.replace('{title}', () => removing.title) : null}
        confirmLabel={t.goals.milestones.delete}
        confirmIcon={<Trash2 className="h-4 w-4" />}
        variant="danger"
        isPending={removeMilestone.isPending}
      />
    </section>
  );
}
