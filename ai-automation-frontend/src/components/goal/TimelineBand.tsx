import { useId, useRef, useState, type ReactNode, type RefObject } from 'react';
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
import { LABEL_COLUMN, PackageRowView, UndatedToggle } from './TimelineRows';
import { TimelineChip } from './TimelineChip';
import { WorkPackageMark } from './WorkPackageMark';
import { DecisionCounts } from './DecisionCounts';
import type { FloatingAnchor } from './floating';
import type { GoalWorkPackage } from '../../types';

/** Wo ein neues Paket gerade eingetippt wird: ab einem Tag (eine Woche) oder ohne Zeitraum in der Leiste. */
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
  /** `trigger`: Balken, Zeilenname oder Eintrag der Leiste, der geklickt wurde – dort steht die Karte, er bekommt danach den Fokus. */
  onOpenPackage: (id: string, trigger: HTMLElement) => void;
  onReschedule: (workPackage: GoalWorkPackage, range: DayRange | null) => void;
  announce: (text: string) => void;
}

/**
 * Ein Projektband des Zeitstrahls (c60bf914): Kopfzeile mit dem Projekt – ein Klick hinein (oder Tag
 * per Tastatur wählen, Enter) legt ab diesem Tag ein Arbeitspaket für eine Woche an, nur der Titel
 * fehlt; „+ Arbeitspaket“ legt eins ohne Zeitraum an –, darunter je Paket eine Zeile mit seinem
 * Balken, „Ohne Arbeitspaket“ nur bei Bedarf, zuletzt die Leiste „Ohne Zeitraum“.
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
  // Auslöser der Eingabefelder – sie bekommen nach Anlegen oder Abbrechen den Fokus zurück
  const addRef = useRef<HTMLButtonElement>(null);
  const pickerRef = useRef<HTMLDivElement>(null);
  const inlineX = inline?.x ?? null;
  const pointAnchor: FloatingAnchor = () => {
    const rect = pickerRef.current?.getBoundingClientRect();
    return rect && inlineX !== null ? new DOMRect(rect.left + inlineX - 12, rect.top, 0, rect.height) : null;
  };
  // „Ohne Arbeitspaket“ und die Leiste nur, wenn dort etwas steht – oder gerade hinein soll
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

/** Kopfzeile eines Bands als Fläche: Balken dieses Projekts lassen sich hierher auf einen Tag ziehen. */
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
 * Leiste „Ohne Zeitraum“: Pakete ohne Zeitraum, auf die Achse gezogen bekommen sie eine Woche; ein
 * Balken hierher verliert seinen. Die TODOs ohne Datum eines Pakets klappen unter der Leiste auf.
 */
function UnscheduledTray({ band, adding, creating, addRef, today, workPackages, onPlan, onOpenTodo, onCreate, onCancel, onOpenPackage }: {
  band: TimelineBand;
  adding: boolean;
  creating: boolean;
  /** Knopf „+ Arbeitspaket“ des Bands – er bekommt nach Anlegen oder Abbrechen den Fokus zurück. */
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
  const [open, setOpen] = useState<string | null>(null);
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
 * Ein Paket ohne Zeitraum: ziehbar auf die Achse (bekommt eine Woche); TODO-Chips darauf abgelegt
 * kommen hinein. Der Zähler daneben klappt seine TODOs ohne Datum auf.
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
  const label = tw.pill.replace('{title}', workPackage.title)
    .replace('{done}', String(workPackage.done)).replace('{total}', String(workPackage.total));
  return (
    <div ref={setDropRef} data-testid={`timeline-pill-${workPackage.id}`}
      className={`relative flex items-center gap-1 rounded-md ${dropTone(accepts, isOver)} ${isOver && accepts ? 'ring-2 ring-accent' : ''}`}>
      <button ref={setDragRef} type="button" {...pointerOnly(listeners)} aria-label={label} title={label}
        onClick={(event) => onOpen(event.currentTarget)}
        className={`flex h-7 max-w-[15rem] cursor-grab items-center gap-1.5 rounded-md border border-dashed border-border-strong bg-surface-raised px-2 text-xs shadow-sm focus-ring active:cursor-grabbing ${
          isDragging ? 'opacity-40' : ''}`}
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
