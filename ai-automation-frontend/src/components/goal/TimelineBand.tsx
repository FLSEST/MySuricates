import { useContext, useId, useRef, useState, type ReactNode, type RefObject } from 'react';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import { Plus } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import {
  bandOfPackage, bandOfTodo, dateOfDay, pillDropId, stripDropId, trayDropId,
  type DayRange, type PlanChanges, type TimelineAxis, type TimelineBand, type UnscheduledPackage,
} from './timelineLayout';
import type { ChipDragData } from './timelineKeyboard';
import { acceptsDrop, dropTone, pointerOnly, useDragState } from './timelineDrag';
import { DayPicker } from './DayPicker';
import { InlineTitleField } from './InlineTitleField';
import { LABEL_COLUMN, PackageRowView } from './TimelineRows';
import { UndatedToggle } from './UndatedToggle';
import { UndatedContext } from './timelineUndated';
import { TimelineChip } from './TimelineChip';
import { WorkPackageMark } from './WorkPackageMark';
import { DecisionCounts } from './DecisionCounts';
import { useIsSelected } from './goalSelection';
import type { FloatingAnchor } from './floating';
import type { GoalWorkPackage } from '../../types';

/** Where a new package is being typed right now: from a day (one week) or without a period in the bar. */
export interface InlineAt {
  day: number | null;
  x: number | null;
}

const ICON_BUTTON = 'inline-flex shrink-0 items-center gap-1 rounded px-1 py-0.5 text-xs text-text-secondary hover:bg-surface-hover hover:text-accent focus-ring';
const PILL_DATA: ChipDragData = { anchor: 12, flipped: false, kind: 'package' };

interface BandProps {
  band: TimelineBand;
  axis: TimelineAxis;
  width: number;
  today: number;
  workPackages: GoalWorkPackage[];
  inline: InlineAt | null;
  creating: boolean;
  barHelpId: string;
  onInline: (at: InlineAt | null) => void;
  onCreate: (title: string, day: number | null) => void;
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
  /** `trigger`: bar, row name or bar entry that was clicked - the card stands there, and it gets the focus afterwards. */
  onOpenPackage: (id: string, trigger: HTMLElement) => void;
  onReschedule: (workPackage: GoalWorkPackage, range: DayRange | null) => void;
  announce: (text: string) => void;
}

/**
 * A project band of the timeline (c60bf914): header row with the project - a click into it (or picking a day
 * by keyboard, Enter) creates a work package from that day for one week, only the title
 * is missing; „+ Arbeitspaket“ creates one without a period -, below it one row per package with its
 * bar, „Ohne Arbeitspaket“ only when needed, last the bar „Ohne Zeitraum“.
 */
export function TimelineBandView({
  band, axis, width, today, workPackages, inline, creating, barHelpId,
  onInline, onCreate, onPlan, onOpenTodo, onOpenPackage, onReschedule, announce,
}: BandProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const to = t.goals.outline;
  const tw = t.goals.workPackages;
  const { dragged } = useDragState();
  const name = band.name ?? to.noProject;
  const addLabel = to.addWorkPackageIn.replace('{project}', name);
  // Trigger of the input fields - they get the focus back after creating or cancelling
  const addRef = useRef<HTMLButtonElement>(null);
  const pickerRef = useRef<HTMLDivElement>(null);
  const inlineX = inline?.x ?? null;
  const pointAnchor: FloatingAnchor = () => {
    const rect = pickerRef.current?.getBoundingClientRect();
    return rect && inlineX !== null ? new DOMRect(rect.left + inlineX - 12, rect.top, 0, rect.height) : null;
  };
  // „Ohne Arbeitspaket“ and the bar only if something is in there - or something is about to go in
  const draggingOwnTodo = dragged?.kind === 'todo' && bandOfTodo(dragged.todo) === band.key && dragged.todo.workPackageId !== null;
  const draggingOwnBar = dragged?.kind === 'package' && dragged.mode === 'move' && bandOfPackage(dragged.workPackage) === band.key;
  const addingUnscheduled = !!inline && inline.day === null;
  const showNoPackage = band.noPackage.items.length + band.noPackage.undated.length > 0 || draggingOwnTodo;
  const showTray = band.unscheduled.length > 0 || addingUnscheduled || draggingOwnBar;
  const rowProps = { axis, width, today, workPackages, barHelpId, onPlan, onOpenTodo, onOpenPackage, onReschedule, announce };

  return (
    <section aria-label={t.goals.timeline.band.replace('{project}', name)} data-testid={`timeline-band-${band.key}`}
      className="border-t-2 border-border-strong">
      <div className="flex bg-surface-secondary/60">
        <div className={`${LABEL_COLUMN} flex items-center gap-1 py-1 pl-1 pr-2`}>
          <span className="min-w-0 flex-1 truncate text-sm font-semibold text-text" title={name}>{name}</span>
          {band.name !== null && !band.linked && (
            <span className="shrink-0 truncate rounded-full border border-warning/40 bg-warning-subtle px-1.5 text-[10px] font-medium text-warning">
              {to.notLinked}
            </span>
          )}
          {band.linked && (
            <button ref={addRef} type="button" className={ICON_BUTTON} aria-label={addLabel} title={addLabel}
              onClick={() => onInline({ day: null, x: null })}>
              <Plus className="h-3.5 w-3.5" aria-hidden="true" />
              <span className="hidden lg:inline" aria-hidden="true">{to.addWorkPackage}</span>
            </button>
          )}
        </div>
        <BandStrip band={band}>
          {band.linked && (
            <DayPicker axis={axis} width={width} label={addLabel} title={t.goals.timeline.bandHint} pickerRef={pickerRef}
              testId={`timeline-strip-picker-${band.key}`} onPick={(day, x) => onInline({ day, x })} />
          )}
        </BandStrip>
        {inline && inline.x !== null && inline.day !== null && (
          <InlineTitleField floating={pointAnchor} triggerRef={pickerRef} placeholder={tw.placeholder} pending={creating}
            label={tw.inlineWeek.replace('{project}', name).replace('{date}', formatDate(dateOfDay(inline.day), locale))}
            onSubmit={(title) => onCreate(title, inline.day)} onCancel={() => onInline(null)} />
        )}
      </div>
      {band.rows.map((row) => <PackageRowView key={row.key} row={row} {...rowProps} />)}
      {showNoPackage && <PackageRowView row={band.noPackage} {...rowProps} />}
      {showTray && (
        <UnscheduledTray band={band} adding={addingUnscheduled} creating={creating} addRef={addRef} today={today}
          workPackages={workPackages} onPlan={onPlan} onOpenTodo={onOpenTodo}
          onCreate={(title) => onCreate(title, null)} onCancel={() => onInline(null)} onOpenPackage={onOpenPackage} />
      )}
    </section>
  );
}

/** Header row of a band as a surface: bars of this project can be dragged here onto a day. */
function BandStrip({ band, children }: { band: TimelineBand; children: ReactNode }) {
  const { dragged } = useDragState();
  const id = stripDropId(band.key);
  const accepts = acceptsDrop(dragged, id);
  const { setNodeRef, isOver } = useDroppable({ id });
  return (
    <div ref={setNodeRef} data-testid={`timeline-strip-${band.key}`} data-accepts={accepts === null ? undefined : String(accepts)}
      className={`relative h-8 min-w-0 flex-1 transition-colors ${dropTone(accepts, isOver)}`}>
      {children}
    </div>
  );
}

/**
 * Bar „Ohne Zeitraum“: packages without a period get one week when dragged onto the axis; a
 * bar dragged here loses its period. The undated TODOs of a package unfold below the bar.
 */
function UnscheduledTray({ band, adding, creating, addRef, today, workPackages, onPlan, onOpenTodo, onCreate, onCancel, onOpenPackage }: {
  band: TimelineBand;
  adding: boolean;
  creating: boolean;
  /** Button „+ Arbeitspaket“ of the band - it gets the focus back after creating or cancelling. */
  addRef: RefObject<HTMLButtonElement | null>;
  today: number;
  workPackages: GoalWorkPackage[];
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
  onCreate: (title: string) => void;
  onCancel: () => void;
  onOpenPackage: (id: string, trigger: HTMLElement) => void;
}) {
  const t = useT();
  const tt = t.goals.timeline;
  const tw = t.goals.workPackages;
  const name = band.name ?? t.goals.outline.noProject;
  const listId = useId();
  // Which package shows its undated TODOs is held by the timeline - „Zeigen“ opens it from there (85889063)
  const shared = useContext(UndatedContext);
  const [local, setLocal] = useState<string | null>(null);
  const open = shared ? shared.pill(band.key) : local;
  const setOpen = (next: string | null) => (shared ? shared.setPill(band.key, next) : setLocal(next));
  const { dragged } = useDragState();
  const id = trayDropId(band.key);
  const accepts = acceptsDrop(dragged, id);
  const { setNodeRef, isOver } = useDroppable({ id });
  const opened = band.unscheduled.find((entry) => entry.workPackage.id === open);
  return (
    <div className="flex border-t border-dashed border-border">
      <div className={`${LABEL_COLUMN} py-2 pl-3 pr-2 text-[11px] font-medium text-text-tertiary`}>{tt.unscheduled}</div>
      <div ref={setNodeRef} data-testid={`timeline-tray-${band.key}`} data-accepts={accepts === null ? undefined : String(accepts)}
        className={`relative flex min-h-9 min-w-0 flex-1 flex-wrap items-center gap-1.5 py-1 transition-colors ${dropTone(accepts, isOver)}`}>
        {band.unscheduled.map((entry) => (
          <UnscheduledPill key={entry.workPackage.id} band={band.key} entry={entry} listId={listId}
            open={open === entry.workPackage.id} onToggle={(next) => setOpen(next ? entry.workPackage.id : null)}
            onOpen={(trigger) => onOpenPackage(entry.workPackage.id, trigger)} />
        ))}
        {adding && (
          <InlineTitleField label={tw.inlineUnscheduled.replace('{project}', name)} placeholder={tw.placeholder} pending={creating}
            triggerRef={addRef} onSubmit={onCreate} onCancel={onCancel} />
        )}
        {band.unscheduled.length === 0 && !adding && (
          <span className="text-[11px] text-text-tertiary">{tt.unscheduledHint}</span>
        )}
        <ul id={listId} hidden={!opened} className="flex basis-full flex-wrap gap-1.5 pb-1" aria-label={opened?.workPackage.title}>
          {opened?.undated.map((todo) => (
            <li key={todo.id} className="w-40">
              <TimelineChip todo={todo} workPackages={workPackages} today={today} compact onPlan={onPlan} onOpenTodo={onOpenTodo} />
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/**
 * A package without a period: draggable onto the axis (gets one week); TODO chips dropped on it
 * go into it. The counter next to it unfolds its undated TODOs.
 */
function UnscheduledPill({ band, entry, listId, open, onToggle, onOpen }: {
  band: string;
  entry: UnscheduledPackage;
  listId: string;
  open: boolean;
  onToggle: (open: boolean) => void;
  onOpen: (trigger: HTMLElement) => void;
}) {
  const t = useT();
  const tw = t.goals.workPackages;
  const { workPackage, undated } = entry;
  const { dragged } = useDragState();
  const dropId = pillDropId(band, workPackage.id);
  const accepts = acceptsDrop(dragged, dropId);
  const { setNodeRef: setDropRef, isOver } = useDroppable({ id: dropId });
  const { listeners, setNodeRef: setDragRef, isDragging } = useDraggable({ id: `wp:${workPackage.id}`, data: PILL_DATA });
  const selected = useIsSelected('workpackage', workPackage.id);
  const label = tw.pill.replace('{title}', workPackage.title)
    .replace('{done}', String(workPackage.done)).replace('{total}', String(workPackage.total));
  return (
    <div ref={setDropRef} data-testid={`timeline-pill-${workPackage.id}`}
      className={`relative flex items-center gap-1 rounded-md ${dropTone(accepts, isOver)} ${isOver && accepts ? 'ring-2 ring-accent' : ''}`}>
      <button ref={setDragRef} type="button" {...pointerOnly(listeners)} aria-label={label} title={label}
        onClick={(event) => onOpen(event.currentTarget)}
        aria-current={selected || undefined}
        className={`flex h-7 max-w-[15rem] cursor-grab items-center gap-1.5 rounded-md border border-dashed border-border-strong bg-surface-raised px-2 text-xs shadow-sm focus-ring active:cursor-grabbing ${
          isDragging ? 'opacity-40' : ''} ${selected ? 'ring-2 ring-accent' : ''}`}
        style={{ touchAction: 'none' }}>
        <WorkPackageMark state={workPackage.state} />
        <span className="min-w-0 truncate text-text">{workPackage.title}</span>
        <span className="shrink-0 tabular-nums text-text-tertiary" aria-hidden="true">{workPackage.done}/{workPackage.total}</span>
        <DecisionCounts openQuestions={workPackage.openQuestions} decided={workPackage.decided} />
      </button>
      {undated.length > 0 && (
        <span className="text-[11px] text-text-tertiary">
          <UndatedToggle count={undated.length} open={open} controls={listId} onToggle={onToggle} />
        </span>
      )}
    </div>
  );
}
