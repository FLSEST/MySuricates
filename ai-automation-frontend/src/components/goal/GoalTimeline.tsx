import { useCallback, useId, useMemo, useRef, useState } from 'react';
import {
  DndContext, DragOverlay, KeyboardSensor, MeasuringStrategy, PointerSensor, useSensor, useSensors,
  type Announcements, type DragEndEvent, type DragMoveEvent, type DragStartEvent, type Modifier, type UniqueIdentifier,
} from '@dnd-kit/core';
import { getEventCoordinates } from '@dnd-kit/utilities';
import { Card } from '../ui';
import { Hint } from '../hints/Hint';
import { useI18nStore, useT } from '../../i18n';
import { useElementWidth } from '../../hooks/useElementWidth';
import { usePlanGoalTodo } from '../../hooks/useGoalTimeline';
import { useCreateWorkPackage, useUpdateWorkPackage } from '../../hooks/useGoalWorkPackages';
import { useCreateMilestone, useUpdateMilestone } from '../../hooks/useGoalMilestones';
import {
  CHIP_ANCHOR, DIAMOND_ANCHOR, FALLBACK_WIDTH, axisFor, buildBands, dayAtX, dayOf, dayShift, dropTargetOf,
  dueDateOfDay, hasDays, milestoneAccepts, milestoneMarks, movedRange, packageAccepts, packageRange, planChanges,
  rangeRequest, resizedRange, sameRange, todoAccepts, weekFrom, type DayRange, type DropTarget, type PlanChanges,
} from './timelineLayout';
import { TimelineAxisView } from './TimelineAxisView';
import { LABEL_COLUMN, TimelineLines } from './TimelineRows';
import { TimelineBandView, type InlineAt } from './TimelineBand';
import { TimelineMilestoneRow } from './TimelineMilestoneRow';
import { MilestoneCard } from './TimelineCards';
import { useGoalSelection } from './goalSelection';
import { TimelineOverlay, type DragFace } from './TimelineOverlay';
import { TIMELINE_KEYBOARD_CODES, chipDragData, timelineCollision, timelineKeyboardCoordinates } from './timelineKeyboard';
import { IDLE, TimelineDragContext, type DragState, type Dragged } from './timelineDrag';
import { liveLine } from './dragLabel';
import type { FloatingAnchor } from './floating';
import { UndatedContext, undatedRows, useUndatedDisclosure } from './timelineUndated';
import { UndatedSwitch } from './UndatedToggle';
import { useRevealInView } from './goalReveal';
import type { Goal, GoalMilestone, GoalProject, GoalTodoItem, GoalWorkPackage } from '../../types';

/** What is currently open as a card: a package, a milestone or a new milestone. */
/** The small card on the timeline only exists for a new milestone; existing ones open the side panel. */
type Panel = { kind: 'newMilestone' };

/** Where a title is currently being typed: a new package in a band or a new milestone on a day. */
type Inline =
  | ({ scope: 'band'; band: string } & InlineAt)
  | { scope: 'milestones'; day: number; x: number };


/** Where the dragged item would currently stand: area, day below it and — for a package — the new period. */
interface Hover {
  target: DropTarget | null;
  day: number | null;
  range: DayRange | null;
}

const NO_FACE: DragFace = { width: null, flipped: false, compact: false };

const sameTarget = (a: DropTarget | null, b: DropTarget | null) => JSON.stringify(a) === JSON.stringify(b);
const sameHover = (a: Hover | null, b: Hover) =>
  !!a && a.day === b.day && sameTarget(a.target, b.target) && sameRange(a.range, b.range);

/**
 * When dragging a chip or a diamond, the point that carries the date sits under the
 * mouse pointer — for a chip its status dot, for a diamond its centre. A bar keeps the
 * grip at which it was grabbed. By keyboard there is no pointer: the image starts exactly above
 * the dragged item.
 */
const dotToCursor: Modifier = ({ activatorEvent, active, draggingNodeRect, transform }) => {
  const start = activatorEvent ? getEventCoordinates(activatorEvent) : null;
  const data = chipDragData(active);
  if (!start || !draggingNodeRect || data.kind === 'package') return transform;
  const faceAnchor = data.kind === 'milestone' ? DIAMOND_ANCHOR : data.flipped ? draggingNodeRect.width - CHIP_ANCHOR : CHIP_ANCHOR;
  return {
    ...transform,
    x: transform.x + start.x - (draggingNodeRect.left + faceAnchor),
    y: transform.y + start.y - (draggingNodeRect.top + draggingNodeRect.height / 2),
  };
};

interface GoalTimelineProps {
  goal: Goal;
  todos: GoalTodoItem[];
  workPackages: GoalWorkPackage[];
  milestones: GoalMilestone[];
  /** The goal's projects — one band each. */
  projects: GoalProject[];
  /** Backend before c60bf914: no milestones, only a hint in their row. */
  legacyBackend?: boolean;
  onOpenTodo: (todoId: string) => void;
  /** For tests: the day that counts as „today“. */
  now?: Date;
}

/**
 * Timeline of a goal (TODO e192df18, c60bf914): on top the axis with today and the target date, below
 * the milestones as diamonds — their lines run through all bands —, then per project a band with
 * one row per work package: its bar from start to end, below it, only if needed, TODOs with due dates,
 * TODOs without a date collapsed at the label. Dragging moves bars, diamonds and TODOs;
 * while doing so the place where it lands is shown below the dragged item. TODOs only go into packages of their project.
 */
export function GoalTimeline({ goal, todos, workPackages, milestones, projects, legacyBackend = false, onOpenTodo, now }: GoalTimelineProps) {
  const t = useT();
  const tt = t.goals.timeline;
  const locale = useI18nStore((s) => s.locale);
  const barHelpId = useId();
  const diamondHelpId = useId();
  const [today] = useState(() => dayOf(now ?? new Date()));
  const measureRef = useRef<HTMLDivElement>(null);
  const measured = useElementWidth(measureRef);
  const width = measured > 0 ? measured : FALLBACK_WIDTH;
  const targetDay = goal.targetDate ? dayOf(goal.targetDate) : null;
  const axis = useMemo(
    () => axisFor({ todos, workPackages, milestones, targetDate: goal.targetDate, today }, width),
    [todos, workPackages, milestones, goal.targetDate, today, width],
  );
  const bands = useMemo(
    () => buildBands(todos, workPackages, projects.map(({ projectId, name }) => ({ projectId, name })), axis, width),
    [todos, workPackages, projects, axis, width],
  );
  const { marks, lanes } = useMemo(() => milestoneMarks(milestones, axis, width), [milestones, axis, width]);
  // TODOs without a date (85889063): small rows open, switch on top, „Zeigen“ expands
  const undated = useUndatedDisclosure(goal.id);
  const withUndated = useMemo(() => undatedRows(bands), [bands]);
  const undatedTotal = withUndated.reduce((sum, row) => sum + row.todoIds.length, 0);
  const allUndatedOpen = withUndated.every((row) => undated.value.isOpen(row.rowId, row.todoIds.length));
  const rootRef = useRef<HTMLElement>(null);
  const todoById = useMemo(() => new Map(todos.map((todo) => [todo.id, todo])), [todos]);
  useRevealInView(goal.id, rootRef, (id) => todoById.has(id), (todoId) => {
    const row = withUndated.find((entry) => entry.todoIds.includes(todoId));
    if (row) undated.value.setOpen(row.rowId, true);
    for (const band of bands) {
      const pill = band.unscheduled.find((entry) => entry.undated.some((todo) => todo.id === todoId));
      if (pill) undated.value.setPill(band.key, pill.workPackage.id);
    }
  });
  const packageById = useMemo(() => new Map(workPackages.map((item) => [item.id, item])), [workPackages]);
  const milestoneById = useMemo(() => new Map(milestones.map((item) => [item.id, item])), [milestones]);
  const { mutate: plan } = usePlanGoalTodo(goal.id);
  const createPackage = useCreateWorkPackage(goal.id);
  const { mutate: updatePackage } = useUpdateWorkPackage(goal.id);
  const createMilestone = useCreateMilestone(goal.id);
  const { mutate: updateMilestone } = useUpdateMilestone(goal.id);
  const [dragged, setDragged] = useState<Dragged>(null);
  const [face, setFace] = useState<DragFace>(NO_FACE);
  const [hover, setHover] = useState<Hover | null>(null);
  const [panel, setPanel] = useState<Panel | null>(null);
  // What the open card opened: it stands there, focus goes there afterwards
  const panelTrigger = useRef<HTMLElement | null>(null);
  const [inline, setInline] = useState<Inline | null>(null);
  const [spoken, setSpoken] = useState('');
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
  const reschedule = useCallback((item: GoalWorkPackage, range: DayRange | null) => {
    updatePackage({ id: item.id, data: rangeRequest(range), quiet: true });
  }, [updatePackage]);
  const moveMilestone = useCallback((item: GoalMilestone, day: number) => {
    updateMilestone({ id: item.id, data: { dueDate: dueDateOfDay(day) }, quiet: true });
  }, [updateMilestone]);
  const bandName = (band: string) => bands.find((b) => b.key === band)?.name ?? t.goals.outline.noProject;

  const draggedOf = (id: UniqueIdentifier): Dragged => {
    const [kind, key = ''] = String(id).split(':');
    if (kind === 'todo') {
      const todo = todoById.get(key);
      return todo ? { kind: 'todo', todo } : null;
    }
    if (kind === 'ms') {
      const milestone = milestoneById.get(key);
      return milestone ? { kind: 'milestone', milestone } : null;
    }
    const item = packageById.get(key);
    if (!item) return null;
    if (kind === 'wp-start') return { kind: 'package', workPackage: item, mode: 'start' };
    if (kind === 'wp-end') return { kind: 'package', workPackage: item, mode: 'end' };
    return kind === 'wp' ? { kind: 'package', workPackage: item, mode: packageRange(item) ? 'move' : 'schedule' } : null;
  };

  /**
   * The day under the mouse pointer in the area above which it stands — snapped to whole days. By
   * keyboard (no pointer) the day under the point of the lifted item.
   */
  const dropDay = (event: DragMoveEvent | DragEndEvent): number | null => {
    if (!event.over) return null;
    const start = getEventCoordinates(event.activatorEvent);
    const lifted = event.active.rect.current.translated;
    const x = start ? start.x + event.delta.x : lifted ? lifted.left + chipDragData(event.active).anchor : null;
    return x === null ? null : dayAtX(axis, x - event.over.rect.left, width);
  };

  /** Where the dragged item would currently stand: area, day and for packages the new period (moved, scheduled, stretched). */
  const evaluate = (event: DragMoveEvent | DragEndEvent, item: NonNullable<Dragged>): Hover => {
    const target = event.over ? dropTargetOf(String(event.over.id)) : null;
    const day = hasDays(target) ? dropDay(event) : null;
    if (item.kind !== 'package') return { target, day, range: null };
    const current = packageRange(item.workPackage);
    const shift = dayShift(axis, event.delta.x, width);
    if (item.mode === 'start' || item.mode === 'end') return { target, day, range: current ? resizedRange(current, item.mode, shift) : null };
    if (item.mode === 'move') return { target, day, range: current ? movedRange(current, shift) : null };
    return { target, day, range: day !== null ? weekFrom(day) : null };
  };

  const handleDragStart = (event: DragStartEvent) => {
    const item = draggedOf(event.active.id);
    const data = chipDragData(event.active);
    setDragged(item);
    setFace({ width: event.active.rect.current.initial?.width ?? null, flipped: data.flipped, compact: !!data.compact });
    setHover(null);
    setPanel(null);
    setInline(null);
  };

  const handleDragMove = (event: DragMoveEvent) => {
    const item = dragged ?? draggedOf(event.active.id);
    if (!item) return;
    const next = evaluate(event, item);
    setHover((current) => (sameHover(current, next) ? current : next));
  };

  const reset = () => {
    setDragged(null);
    setHover(null);
    setFace(NO_FACE);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const item = draggedOf(event.active.id);
    reset();
    if (!item) return;
    const { target, day, range } = evaluate(event, item);
    if (item.kind === 'package' && (item.mode === 'start' || item.mode === 'end')) {
      if (range && !sameRange(range, packageRange(item.workPackage))) reschedule(item.workPackage, range);
      return;
    }
    if (!target) return;
    if (item.kind === 'milestone') {
      if (milestoneAccepts(target) && day !== null && day !== dayOf(item.milestone.dueDate)) moveMilestone(item.milestone, day);
      return;
    }
    if (item.kind === 'package') {
      if (!packageAccepts(item.workPackage, target)) return;
      if (target.kind === 'tray') {
        if (packageRange(item.workPackage)) reschedule(item.workPackage, null);
      } else if (range && !sameRange(range, packageRange(item.workPackage))) {
        reschedule(item.workPackage, range);
      }
      return;
    }
    if (!todoAccepts(item.todo, target) || (hasDays(target) && day === null)) return;
    const changes = planChanges(item.todo, target, day);
    if (Object.keys(changes).length > 0) plan({ todoId: item.todo.id, changes });
  };

  const live = dragged && hover
    ? liveLine(dragged, hover.target, hover.day, hover.range, {
      t, locale, bandName, packageTitle: (id) => packageById.get(id)?.title,
    })
    : null;
  const dragState: DragState = dragged ? {
    dragged,
    preview: dragged.kind === 'package' ? { workPackageId: dragged.workPackage.id, range: hover?.range ?? null } : null,
    live,
  } : IDLE;
  const previewDays = !dragged || !hover || live?.refused ? []
    : dragged.kind === 'package' ? (hover.range && hover.target?.kind !== 'tray' ? [hover.range.start, hover.range.end] : [])
      : hover.day !== null ? [hover.day] : [];

  const titleOf = (id: UniqueIdentifier) => {
    const item = draggedOf(id);
    if (!item) return '';
    return item.kind === 'todo' ? item.todo.title : item.kind === 'milestone' ? item.milestone.title : item.workPackage.title;
  };
  const announcements: Announcements = {
    onDragStart: ({ active }) => tt.announce.start.replace('{title}', titleOf(active.id)),
    onDragOver: ({ over }) => {
      if (!over || !live) return tt.announce.outside;
      return tt.announce.over.replace('{row}', live.text);
    },
    onDragEnd: ({ active }) => tt.announce.end.replace('{title}', titleOf(active.id)),
    onDragCancel: () => tt.announce.cancel,
  };

  const openPanel = (next: Panel, trigger: HTMLElement) => {
    setInline(null);
    panelTrigger.current = trigger;
    setPanel(next);
  };
  const panelAnchor = useCallback<FloatingAnchor>(() => panelTrigger.current?.getBoundingClientRect() ?? null, []);
  // Milestones and work packages open the goal page's side panel (c60bf914)
  const selection = useGoalSelection();
  const openDetails = (kind: 'milestone' | 'workpackage', id: string, trigger: HTMLElement) => {
    setInline(null);
    setPanel(null);
    selection?.select({ kind, id }, trigger);
  };

  const addPackage = (band: string, title: string, day: number | null) => createPackage.mutate(
    { title, projectId: band, ...(day !== null ? rangeRequest(weekFrom(day)) : {}) },
    { onSuccess: () => setInline(null) },
  );
  const addMilestone = (title: string, day: number) => createMilestone.mutate(
    { title, dueDate: dueDateOfDay(day) },
    { onSuccess: () => setInline(null) },
  );
  const pickMilestoneDay = (day: number, x: number) => {
    setPanel(null);
    setInline({ scope: 'milestones', day, x });
  };

  return (
    <section ref={rootRef} aria-label={tt.label} data-testid="goal-timeline" className="space-y-3">
      <Hint id="goal-timeline" />
      {undatedTotal > 0 && <UndatedSwitch total={undatedTotal} allOpen={allUndatedOpen} onChange={undated.setMode} />}
      <p id={barHelpId} className="sr-only">{tt.barHelp}</p>
      <p id={diamondHelpId} className="sr-only">{tt.diamondHelp}</p>
      <p aria-live="polite" className="sr-only" data-testid="timeline-announcer">{spoken}</p>

      <DndContext
        sensors={sensors}
        collisionDetection={timelineCollision}
        measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
        accessibility={{ announcements, screenReaderInstructions: { draggable: tt.dragHelp } }}
        onDragStart={handleDragStart}
        onDragMove={handleDragMove}
        onDragEnd={handleDragEnd}
        onDragCancel={reset}
      >
        <UndatedContext.Provider value={undated.value}>
        <TimelineDragContext.Provider value={dragState}>
          <Card padding={false} className="p-3">
            {/* On narrow screens the timeline scrolls within itself, not the page */}
            <div className="max-sm:-mx-3 max-sm:overflow-x-auto max-sm:px-3">
              <div className="relative max-sm:min-w-[36rem]">
                <TimelineLines axis={axis} width={width} targetDay={targetDay} marks={legacyBackend ? [] : marks} preview={previewDays} />
                <div className="flex">
                  <div className={LABEL_COLUMN} />
                  <div className="relative min-w-0 flex-1">
                    <TimelineAxisView axis={axis} width={width} targetDay={targetDay} measureRef={measureRef}
                      onCreateAt={legacyBackend ? () => undefined : pickMilestoneDay} />
                  </div>
                </div>
                {legacyBackend ? (
                  <div className="flex border-t border-border" data-testid="timeline-milestones-legacy">
                    <div className={`${LABEL_COLUMN} py-2 pl-1 text-xs font-semibold text-text-secondary`}>{tt.milestoneRow}</div>
                    <p className="py-2 text-xs text-text-tertiary">{t.goals.milestones.legacy}</p>
                  </div>
                ) : (
                  <TimelineMilestoneRow marks={marks} lanes={lanes} axis={axis} width={width}
                    inline={inline?.scope === 'milestones' ? inline : null} creating={createMilestone.isPending} helpId={diamondHelpId}
                    onPick={pickMilestoneDay}
                    onCreate={addMilestone}
                    onCancel={() => setInline(null)}
                    onAdd={(trigger) => openPanel({ kind: 'newMilestone' }, trigger)}
                    onOpen={(item, trigger) => openDetails('milestone', item.id, trigger)}
                    onMove={moveMilestone} announce={setSpoken} />
                )}
                {bands.length === 0 && (
                  <p className="border-t border-border px-1 py-4 text-sm text-text-secondary" data-testid="timeline-no-projects">
                    {tt.noProjects}
                  </p>
                )}
                {bands.map((band) => (
                  <TimelineBandView key={band.key} band={band} axis={axis} width={width} today={today} workPackages={workPackages}
                    inline={inline?.scope === 'band' && inline.band === band.key ? inline : null} creating={createPackage.isPending}
                    barHelpId={barHelpId}
                    onInline={(at) => {
                      setPanel(null);
                      setInline(at ? { scope: 'band', band: band.key, ...at } : null);
                    }}
                    onCreate={(title, day) => addPackage(band.key, title, day)}
                    onPlan={planFromMenu} onOpenTodo={onOpenTodo}
                    onOpenPackage={(id, trigger) => openDetails('workpackage', id, trigger)}
                    onReschedule={reschedule} announce={setSpoken} />
                ))}
              </div>
            </div>
          </Card>
        </TimelineDragContext.Provider>
        </UndatedContext.Provider>
        {panel?.kind === 'newMilestone' && (
          <MilestoneCard goalId={goal.id} day={Math.min(axis.endDay, Math.max(axis.startDay, today))} workPackages={workPackages}
            projects={projects} onClose={closePanel} anchor={panelAnchor} triggerRef={panelTrigger} align="start" />
        )}
        <DragOverlay modifiers={[dotToCursor]} dropAnimation={null}>
          {dragged && !(dragged.kind === 'package' && (dragged.mode === 'start' || dragged.mode === 'end')) && (
            <TimelineOverlay dragged={dragged} face={face} live={live} today={today} />
          )}
        </DragOverlay>
      </DndContext>
    </section>
  );
}
