import { useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import { Plus } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import {
  CHIP_ANCHOR, DIAMOND_ANCHOR, dateOfDay, dayAtX, dayOf, pillDropId, stripDropId, trayDropId, xOfDay,
  type PlanChanges, type TimelineAxis, type TimelineBand,
} from './timelineLayout';
import type { ChipDragData } from './timelineKeyboard';
import { acceptsDrop, dropTone, useDragged } from './timelineDrag';
import { MAX_SUBGOAL_TITLE } from './subgoalForm';
import { MilestoneDiamond } from './MilestoneDiamond';
import { FLOATING_STYLE, returnFocus, useFloating, type FloatingAnchor } from './floating';
import { LABEL_COLUMN, TimelineRowView } from './TimelineRows';
import type { DatedMilestone, GoalMilestone } from '../../types';

/** Wo ein neues Teilziel gerade eingetippt wird: an einem Tag in der Kopfzeile oder ohne Datum in der Leiste. */
export interface InlineAt {
  day: number | null;
  x: number | null;
}

/** Ein Feld im Fluss schwebt nicht. */
const NO_ANCHOR: FloatingAnchor = () => null;

const ICON_BUTTON = 'inline-flex shrink-0 items-center gap-1 rounded px-1 py-0.5 text-xs text-text-secondary hover:bg-surface-hover hover:text-accent focus-ring';

interface BandProps {
  band: TimelineBand;
  axis: TimelineAxis;
  width: number;
  today: number;
  milestones: GoalMilestone[];
  inline: InlineAt | null;
  creating: boolean;
  onInline: (at: InlineAt | null) => void;
  onCreate: (title: string, day: number | null) => void;
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
  /** `trigger`: Raute, Eintrag oder Zeilenname, der geklickt wurde – dort steht die Karte, er bekommt danach den Fokus. */
  onOpenMilestone: (id: string, trigger: HTMLElement) => void;
}

/**
 * Ein Projektband des Zeitstrahls (c60bf914): Kopfzeile mit dem Projekt und den Rauten seiner
 * datierten Teilziele – ein Klick hinein (oder Tag per Tastatur wählen, Enter) legt dort ein Teilziel
 * an, nur der Titel fehlt –, darunter je Teilziel eine Zeile mit seinen TODOs, „ohne Teilziel“ und die
 * Leiste „ohne Datum“.
 */
export function TimelineBandView({
  band, axis, width, today, milestones, inline, creating, onInline, onCreate, onPlan, onOpenTodo, onOpenMilestone,
}: BandProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const to = t.goals.outline;
  const name = band.name ?? to.noProject;
  const addLabel = to.addSubgoalIn.replace('{project}', name);
  // Auslöser der Eingabefelder – sie bekommen nach Anlegen oder Abbrechen den Fokus zurück
  const addRef = useRef<HTMLButtonElement>(null);
  const pickerRef = useRef<HTMLDivElement>(null);
  const inlineX = inline?.x ?? null;
  const pointAnchor: FloatingAnchor = () => {
    const rect = pickerRef.current?.getBoundingClientRect();
    return rect && inlineX !== null ? new DOMRect(rect.left + inlineX - 12, rect.top, 0, rect.height) : null;
  };
  return (
    <section aria-label={t.goals.timeline.band.replace('{project}', name)} data-testid={`timeline-band-${band.key}`}
      className="border-t-2 border-border-strong">
      <div className="flex bg-surface-secondary/60">
        <div className={`${LABEL_COLUMN} flex items-center gap-1 py-1.5 pl-1 pr-2`}>
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
              <span className="hidden sm:inline" aria-hidden="true">{to.addSubgoal}</span>
            </button>
          )}
        </div>
        <BandStrip band={band} axis={axis} width={width} addLabel={addLabel} pickerRef={pickerRef}
          onPick={(day, x) => onInline({ day, x })}>
          {band.dated.map((milestone) => (
            <StripDiamond key={milestone.id} milestone={milestone} x={xOfDay(axis, dayOf(milestone.dueDate), width)}
              onOpen={(trigger) => onOpenMilestone(milestone.id, trigger)} />
          ))}
        </BandStrip>
        {inline && inline.x !== null && inline.day !== null && (
          <InlineSubgoalField floating={pointAnchor} triggerRef={pickerRef}
            label={t.goals.milestones.inline.replace('{project}', name).replace('{date}', formatDate(dateOfDay(inline.day), locale))}
            pending={creating} onSubmit={(title) => onCreate(title, inline.day)} onCancel={() => onInline(null)} />
        )}
      </div>
      {band.rows.map((row) => (
        <TimelineRowView key={row.key} row={row} axis={axis} width={width} today={today} milestones={milestones}
          onPlan={onPlan} onOpenTodo={onOpenTodo} onOpenMilestone={onOpenMilestone} />
      ))}
      {/* Ohne eigenes Projekt lässt sich nichts anlegen – die Leiste zeigt dort nur, was schon wartet */}
      {(band.linked || band.undated.length > 0) && (
        <UndatedTray band={band} adding={!!inline && inline.day === null} creating={creating} addRef={addRef}
          onCreate={(title) => onCreate(title, null)} onCancel={() => onInline(null)} onOpenMilestone={onOpenMilestone} />
      )}
    </section>
  );
}

/**
 * Kopfzeile eines Bands: Drop-Fläche für die Rauten dieses Projekts und – bei einem Projekt des Ziels
 * – ein Tageswähler (Rolle „slider“): Klick legt am Tag darunter an, per Tastatur wählen ← → (Bild ↑ ↓
 * je Woche, Pos1/Ende) den Tag, Enter oder Leertaste legt an.
 */
function BandStrip({ band, axis, width, addLabel, pickerRef, onPick, children }: {
  band: TimelineBand;
  axis: TimelineAxis;
  width: number;
  addLabel: string;
  pickerRef: RefObject<HTMLDivElement | null>;
  onPick: (day: number, x: number) => void;
  children: ReactNode;
}) {
  const tt = useT().goals.timeline;
  const locale = useI18nStore((s) => s.locale);
  const helpId = useId();
  const id = stripDropId(band.key);
  const accepts = acceptsDrop(useDragged(), id);
  const { setNodeRef, isOver } = useDroppable({ id, disabled: accepts === false });
  const [cursor, setCursor] = useState<number | null>(null);
  const clamp = (day: number) => Math.min(axis.endDay, Math.max(axis.startDay, day));
  const shown = cursor ?? clamp(axis.today);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, PageUp: -7, PageDown: 7 };
    if (event.key in step) setCursor(clamp(shown + step[event.key]!));
    else if (event.key === 'Home') setCursor(axis.startDay);
    else if (event.key === 'End') setCursor(axis.endDay);
    else if (event.key === 'Enter' || event.key === ' ') onPick(shown, xOfDay(axis, shown, width));
    else return;
    event.preventDefault();
  };

  return (
    <div ref={setNodeRef} data-testid={`timeline-strip-${band.key}`} data-accepts={accepts === null ? undefined : String(accepts)}
      className={`relative h-9 min-w-0 flex-1 transition-colors ${dropTone(accepts, isOver)}`}>
      {band.linked && (
        <>
          <span id={helpId} className="sr-only">{tt.bandStrip}</span>
          <div ref={pickerRef} role="slider" tabIndex={0} aria-label={addLabel} aria-describedby={helpId} title={tt.bandStripHint}
            aria-valuemin={axis.startDay} aria-valuemax={axis.endDay} aria-valuenow={shown}
            aria-valuetext={formatDate(dateOfDay(shown), locale)}
            data-testid={`timeline-strip-picker-${band.key}`}
            onKeyDown={onKeyDown}
            onFocus={() => setCursor((current) => current ?? clamp(axis.today))}
            onBlur={() => setCursor(null)}
            onClick={(event) => {
              const px = event.clientX - event.currentTarget.getBoundingClientRect().left;
              onPick(dayAtX(axis, px, width), px);
            }}
            className="group absolute inset-0 cursor-copy rounded-sm focus-ring">
            {cursor !== null && (
              <span aria-hidden="true" className="absolute inset-y-1 w-0.5 -translate-x-1/2 rounded bg-accent"
                style={{ left: xOfDay(axis, cursor, width) }} />
            )}
          </div>
        </>
      )}
      {children}
    </div>
  );
}

/** Die Raute eines datierten Teilziels in der Kopfzeile: Klick öffnet die Karte, Ziehen (Maus oder Leertaste) verschiebt das Datum. */
function StripDiamond({ milestone, x, onOpen }: { milestone: DatedMilestone; x: number; onOpen: (trigger: HTMLElement) => void }) {
  const tm = useT().goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const data: ChipDragData = { anchor: DIAMOND_ANCHOR, flipped: false, kind: 'milestone' };
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `ms:${milestone.id}`, data });
  const label = tm.diamond.replace('{title}', milestone.title).replace('{state}', tm.states[milestone.state])
    .replace('{date}', formatDate(milestone.dueDate, locale));
  return (
    <div className="absolute inset-y-0 z-10 flex items-center" style={{ left: x - DIAMOND_ANCHOR, width: 2 * DIAMOND_ANCHOR }}>
      <button ref={setNodeRef} type="button" {...attributes} {...listeners} aria-label={label} title={label}
        onClick={(event) => onOpen(event.currentTarget)}
        className={`flex h-5 w-5 cursor-grab items-center justify-center rounded hover:bg-surface-hover focus-ring active:cursor-grabbing ${
          isDragging ? 'opacity-40' : ''}`}
        style={{ touchAction: 'none' }}>
        <MilestoneDiamond state={milestone.state} size="lg" />
      </button>
    </div>
  );
}

/** Leiste „ohne Datum“: Teilziele ohne Datum, ziehbar auf das Band; eine Raute hierher nimmt ihr Datum weg. */
function UndatedTray({ band, adding, creating, addRef, onCreate, onCancel, onOpenMilestone }: {
  band: TimelineBand;
  adding: boolean;
  creating: boolean;
  /** Knopf „+ Teilziel“ des Bands – er bekommt nach Anlegen oder Abbrechen den Fokus zurück. */
  addRef: RefObject<HTMLButtonElement | null>;
  onCreate: (title: string) => void;
  onCancel: () => void;
  onOpenMilestone: (id: string, trigger: HTMLElement) => void;
}) {
  const t = useT();
  const tt = t.goals.timeline;
  const name = band.name ?? t.goals.outline.noProject;
  const id = trayDropId(band.key);
  const accepts = acceptsDrop(useDragged(), id);
  const { setNodeRef, isOver } = useDroppable({ id, disabled: accepts === false });
  return (
    <div className="flex border-t border-dashed border-border">
      <div className={`${LABEL_COLUMN} py-2 pl-3 pr-2 text-[11px] font-medium text-text-tertiary`}>{tt.undated}</div>
      <div ref={setNodeRef} data-testid={`timeline-tray-${band.key}`} data-accepts={accepts === null ? undefined : String(accepts)}
        className={`relative flex min-h-10 min-w-0 flex-1 flex-wrap items-center gap-1.5 py-1.5 transition-colors ${dropTone(accepts, isOver)}`}>
        {band.undated.map((milestone) => (
          <UndatedPill key={milestone.id} band={band.key} milestone={milestone}
            onOpen={(trigger) => onOpenMilestone(milestone.id, trigger)} />
        ))}
        {adding && (
          <InlineSubgoalField label={t.goals.milestones.inlineUndated.replace('{project}', name)} pending={creating}
            triggerRef={addRef} onSubmit={onCreate} onCancel={onCancel} />
        )}
        {band.undated.length === 0 && !adding && (
          <span className="text-[11px] text-text-tertiary">{band.linked ? tt.undatedHint : tt.undatedEmpty}</span>
        )}
      </div>
    </div>
  );
}

/** Ein Teilziel ohne Datum: ziehbar auf das Band (setzt ein Datum), TODO-Chips darauf abgelegt kommen hinein. */
function UndatedPill({ band, milestone, onOpen }: {
  band: string;
  milestone: GoalMilestone;
  onOpen: (trigger: HTMLElement) => void;
}) {
  const tm = useT().goals.milestones;
  const dropId = pillDropId(band, milestone.id);
  const accepts = acceptsDrop(useDragged(), dropId);
  const { setNodeRef: setDropRef, isOver } = useDroppable({ id: dropId, disabled: accepts === false });
  const data: ChipDragData = { anchor: CHIP_ANCHOR, flipped: false, kind: 'milestone' };
  const { attributes, listeners, setNodeRef: setDragRef, isDragging } = useDraggable({ id: `ms:${milestone.id}`, data });
  const label = tm.undatedPill.replace('{title}', milestone.title).replace('{state}', tm.states[milestone.state]);
  return (
    <div ref={setDropRef} data-testid={`timeline-pill-${milestone.id}`}
      className={`relative rounded-md ${dropTone(accepts, isOver)} ${isOver && accepts ? 'ring-2 ring-accent' : ''}`}>
      <button ref={setDragRef} type="button" {...attributes} {...listeners} aria-label={label} title={label}
        onClick={(event) => onOpen(event.currentTarget)}
        className={`flex h-7 max-w-[14rem] cursor-grab items-center gap-1.5 rounded-md border border-border bg-surface-raised px-[7px] text-xs shadow-sm focus-ring active:cursor-grabbing ${
          isDragging ? 'opacity-40' : ''}`}
        style={{ touchAction: 'none' }}>
        <MilestoneDiamond state={milestone.state} />
        <span className="min-w-0 truncate text-text">{milestone.title}</span>
        <span className="shrink-0 tabular-nums text-text-tertiary" aria-hidden="true">{milestone.done}/{milestone.total}</span>
      </button>
    </div>
  );
}

/**
 * Das Feld für ein neues Teilziel am Ort: Projekt und Tag stehen fest, nur der Titel fehlt. Enter
 * legt an, Escape bricht ab, ein leeres Feld verschwindet beim Verlassen. Danach bekommt der
 * Auslöser (Tageswähler oder „+ Teilziel“) den Fokus zurück. Am Tag schwebt es per Portal unter der
 * Kopfzeile (`floating.ts`), damit der in sich scrollende Zeitstrahl es nicht abschneidet.
 */
function InlineSubgoalField({ label, pending, triggerRef, floating, onSubmit, onCancel }: {
  label: string;
  pending: boolean;
  triggerRef: RefObject<HTMLElement | null>;
  /** Anker, wenn das Feld schwebt; ohne steht es im Fluss (Leiste „ohne Datum“). */
  floating?: FloatingAnchor;
  onSubmit: (title: string) => void;
  onCancel: () => void;
}) {
  const tm = useT().goals.milestones;
  const [title, setTitle] = useState('');
  const ref = useRef<HTMLFormElement>(null);
  useFloating(floating ?? NO_ANCHOR, ref, 'start');
  useLayoutEffect(() => {
    const node = ref.current;
    const trigger = triggerRef.current;
    return () => returnFocus(trigger, node);
  }, [triggerRef]);

  const form = (
    <form ref={ref} data-testid="inline-subgoal" style={floating ? FLOATING_STYLE : undefined}
      className={floating ? 'z-50 w-56 max-w-[calc(100vw-1rem)]' : 'w-56 max-w-full'}
      onSubmit={(event) => {
        event.preventDefault();
        if (title.trim() && !pending) onSubmit(title.trim());
      }}>
      {/* Der Fokus springt bewusst hinein: das Feld erscheint auf einen Klick oder Enter hin */}
      <input autoFocus aria-label={label} placeholder={tm.placeholder} value={title} maxLength={MAX_SUBGOAL_TITLE}
        disabled={pending}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== 'Escape') return;
          event.preventDefault();
          event.stopPropagation();
          onCancel();
        }}
        onBlur={() => {
          if (!title.trim()) onCancel();
        }}
        className="input-base w-full py-1 text-sm shadow-lg" />
    </form>
  );
  return floating ? createPortal(form, document.body) : form;
}
