import { useRef, type KeyboardEvent } from 'react';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import { Plus } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { DIAMOND_ANCHOR, MILESTONES_DROP_ID, dateOfDay, xOfDay, type MilestoneMark, type TimelineAxis } from './timelineLayout';
import type { ChipDragData } from './timelineKeyboard';
import { acceptsDrop, dropTone, pointerOnly, useDragState } from './timelineDrag';
import { dayText } from './dragLabel';
import { DayPicker } from './DayPicker';
import { InlineTitleField } from './InlineTitleField';
import { MilestoneDiamond } from './MilestoneDiamond';
import { LABEL_COLUMN } from './TimelineRows';
import { useKeyboardNudge } from './useKeyboardNudge';
import { useIsSelected } from './goalSelection';
import type { FloatingAnchor } from './floating';
import type { GoalMilestone } from '../../types';

/** Höhe einer Spur der Meilenstein-Zeile (Raute mit Titel). */
export const MILESTONE_LANE = 24;
const DRAG_DATA: ChipDragData = { anchor: DIAMOND_ANCHOR, flipped: false, kind: 'milestone' };
const ICON_BUTTON = 'inline-flex shrink-0 items-center rounded p-1 text-text-secondary hover:bg-surface-hover hover:text-accent focus-ring';

/**
 * Die Meilenstein-Zeile oben im Zeitstrahl (c60bf914): Rauten an ihrem Datum, Farbe nach Zustand,
 * der Titel daneben. Ein Klick in die Zeile (oder Tag per Tastatur wählen, Enter) legt an diesem Tag
 * einen Meilenstein an – nur der Titel fehlt; „+“ öffnet das Formular. Rauten lassen sich ziehen
 * (neues Datum) und per Tastatur verschieben; Klick oder Enter öffnet ihre Karte.
 */
export function TimelineMilestoneRow({
  marks, lanes, axis, width, inline, creating, helpId, onPick, onCreate, onCancel, onAdd, onOpen, onMove, announce,
}: {
  marks: MilestoneMark[];
  lanes: number;
  axis: TimelineAxis;
  width: number;
  /** Ein neuer Meilenstein, der gerade eingetippt wird: sein Tag und wo das Feld steht. */
  inline: { day: number; x: number } | null;
  creating: boolean;
  /** ID des Hilfetexts zur Tastatur an den Rauten. */
  helpId: string;
  onPick: (day: number, x: number) => void;
  onCreate: (title: string, day: number) => void;
  onCancel: () => void;
  onAdd: (trigger: HTMLElement) => void;
  onOpen: (milestone: GoalMilestone, trigger: HTMLElement) => void;
  onMove: (milestone: GoalMilestone, day: number) => void;
  announce: (text: string) => void;
}) {
  const t = useT();
  const tt = t.goals.timeline;
  const tm = t.goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const { dragged } = useDragState();
  const accepts = acceptsDrop(dragged, MILESTONES_DROP_ID);
  const { setNodeRef, isOver } = useDroppable({ id: MILESTONES_DROP_ID });
  const pickerRef = useRef<HTMLDivElement>(null);
  const addRef = useRef<HTMLButtonElement>(null);
  const inlineX = inline?.x ?? null;
  const pointAnchor: FloatingAnchor = () => {
    const rect = pickerRef.current?.getBoundingClientRect();
    return rect && inlineX !== null ? new DOMRect(rect.left + inlineX - 12, rect.top, 0, rect.height) : null;
  };

  return (
    <div className="flex border-t border-border" data-testid="timeline-milestones">
      <div className={`${LABEL_COLUMN} flex items-center gap-1 py-1 pl-1 pr-2`}>
        <span className="min-w-0 flex-1 truncate text-xs font-semibold text-text-secondary">{tt.milestoneRow}</span>
        <button ref={addRef} type="button" className={ICON_BUTTON} aria-label={tm.create} title={tm.create}
          onClick={(event) => onAdd(event.currentTarget)}>
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
      <div ref={setNodeRef} data-accepts={accepts === null ? undefined : String(accepts)}
        className={`relative min-w-0 flex-1 transition-colors ${dropTone(accepts, isOver)}`}
        style={{ height: Math.max(32, lanes * MILESTONE_LANE + 8) }}>
        <DayPicker axis={axis} width={width} label={tm.create} title={tt.milestoneRowHint} pickerRef={pickerRef}
          testId="timeline-milestone-picker" onPick={onPick} />
        {marks.map((mark) => (
          <MilestoneMarkButton key={mark.milestone.id} mark={mark} axis={axis} width={width} helpId={helpId}
            onOpen={(trigger) => onOpen(mark.milestone, trigger)}
            onMove={(day) => onMove(mark.milestone, day)}
            onNudge={(day) => announce(t.goals.timeline.announce.moved.replace('{title}', mark.milestone.title)
              .replace('{when}', dayText(day, locale)))} />
        ))}
      </div>
      {inline && (
        <InlineTitleField floating={pointAnchor} triggerRef={pickerRef} placeholder={tm.placeholder} pending={creating}
          label={tm.inline.replace('{date}', formatDate(dateOfDay(inline.day), locale))}
          onSubmit={(title) => onCreate(title, inline.day)} onCancel={onCancel} />
      )}
    </div>
  );
}

/** Eine Raute mit ihrem Titel: ziehbar mit der Maus, ← → verschieben um einen Tag, Enter öffnet die Karte. */
function MilestoneMarkButton({ mark, axis, width, helpId, onOpen, onMove, onNudge }: {
  mark: MilestoneMark;
  axis: TimelineAxis;
  width: number;
  helpId: string;
  onOpen: (trigger: HTMLElement) => void;
  onMove: (day: number) => void;
  onNudge: (day: number) => void;
}) {
  const tm = useT().goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const { milestone } = mark;
  const nudge = useKeyboardNudge(mark.day, (a, b) => a === b, onMove);
  const selected = useIsSelected('milestone', milestone.id);
  const { listeners, setNodeRef, isDragging } = useDraggable({ id: `ms:${milestone.id}`, data: DRAG_DATA });
  const x = nudge.active ? xOfDay(axis, nudge.value, width) : mark.x;
  const label = tm.diamond.replace('{title}', milestone.title).replace('{state}', tm.states[milestone.state])
    .replace('{date}', formatDate(dateOfDay(nudge.value), locale));

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      const next = nudge.value + (event.key === 'ArrowRight' ? 1 : -1);
      nudge.nudge(next);
      onNudge(next);
    } else if (event.key === 'Enter') {
      nudge.flush();
      onOpen(event.currentTarget);
    } else if (event.key === 'Escape' && nudge.active) {
      nudge.cancel();
    } else {
      return;
    }
    event.preventDefault();
  };

  return (
    <div className="absolute z-10"
      style={{ left: x - DIAMOND_ANCHOR - (mark.flipped ? mark.labelWidth : 0), top: 4 + mark.lane * MILESTONE_LANE }}
      data-testid={`timeline-milestone-${milestone.id}`} data-day={nudge.value} data-flipped={mark.flipped || undefined}>
      <button ref={setNodeRef} type="button" {...pointerOnly(listeners)} aria-label={label} aria-describedby={helpId} title={label}
        onKeyDown={onKeyDown} onBlur={nudge.flush} onClick={(event) => onOpen(event.currentTarget)}
        className={`flex h-5 cursor-grab items-center rounded hover:bg-surface-hover focus-ring active:cursor-grabbing ${
          mark.flipped ? 'flex-row-reverse pl-1' : 'pr-1'} ${isDragging ? 'opacity-40' : ''} ${selected ? 'bg-accent-subtle ring-2 ring-accent' : ''}`}
        aria-current={selected || undefined} data-selected={selected || undefined}
        style={{ touchAction: 'none', maxWidth: 2 * DIAMOND_ANCHOR + mark.labelWidth }}>
        <span className="flex h-5 w-5 shrink-0 items-center justify-center"><MilestoneDiamond state={milestone.state} size="lg" /></span>
        {mark.labelWidth > 20 && (
          <span className={`min-w-0 truncate text-[11px] font-medium ${mark.flipped ? 'text-right' : ''} ${
            milestone.state === 'REACHED' ? 'text-text-secondary' : 'text-text'}`}>
            {milestone.title}
          </span>
        )}
      </button>
    </div>
  );
}
