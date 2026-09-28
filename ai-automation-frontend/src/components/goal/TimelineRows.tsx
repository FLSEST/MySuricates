import { useId, useState } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { ChevronRight } from 'lucide-react';
import { useT } from '../../i18n';
import {
  BAR_HEIGHT, BAR_TOP, BAR_ZONE, LANE_HEIGHT, barBox, rowDropId, undatedDropId, xOfDay,
  type DayRange, type MilestoneMark, type PackageRow, type PlanChanges, type TimelineAxis,
} from './timelineLayout';
import { acceptsDrop, dropTone, useDragState } from './timelineDrag';
import { TimelineChip } from './TimelineChip';
import { WorkPackageBar } from './WorkPackageBar';
import { WorkPackageMark } from './WorkPackageMark';
import { DecisionCounts } from './DecisionCounts';
import type { GoalWorkPackage, MilestoneState } from '../../types';

/** Abstand der Chips zum oberen Rand einer Zeile ohne Balken. */
const ROW_PADDING = 8;

/** Breite der Beschriftungsspalte – Zeilen, Achse und Linien müssen dieselbe nehmen. */
export const LABEL_COLUMN = 'w-28 shrink-0 sm:w-44 lg:w-52 xl:w-56';
const LINES_LEFT = 'left-28 sm:left-44 lg:left-52 xl:left-56';

/** Führungslinien der Meilensteine: gestrichelt durch alle Bänder, Farbe nach Zustand. */
const GUIDE: Record<MilestoneState, string> = {
  OPEN: 'border-text-tertiary/60',
  DUE_SOON: 'border-warning/80',
  OVERDUE: 'border-danger/80',
  REACHED: 'border-success/70',
};

/**
 * Senkrechte Linien über Achse und Zeilen: Raster, heute, das Zieldatum, die Meilensteine (von ihrer
 * Raute durch alle Bänder – so sieht man, welche Balken vor dem Termin enden) und beim Ziehen die
 * Tage, auf denen es landen würde. Liegt unter Balken und Chips und nimmt keine Klicks an.
 */
export function TimelineLines({ axis, width, targetDay, marks, preview }: {
  axis: TimelineAxis;
  width: number;
  targetDay: number | null;
  marks: MilestoneMark[];
  preview: number[];
}) {
  const perDay = width / axis.days;
  const x = (day: number) => xOfDay(axis, day, width);
  return (
    <div aria-hidden="true" className={`pointer-events-none absolute inset-y-0 right-0 ${LINES_LEFT}`}>
      {axis.ticks.map((tick) => (
        <span key={tick} className="absolute bottom-0 top-10 w-px bg-border/60" style={{ left: (tick - axis.startDay) * perDay }} />
      ))}
      <span className="absolute bottom-0 top-12 w-0.5 -translate-x-1/2 bg-info/80" style={{ left: x(axis.today) }} />
      {targetDay !== null && (
        <span className="absolute bottom-0 top-12 border-l-2 border-dashed border-accent" style={{ left: x(targetDay) - 1 }} />
      )}
      {marks.map((mark) => (
        <span key={mark.milestone.id} data-testid="timeline-guide"
          className={`absolute bottom-0 top-16 border-l border-dashed ${GUIDE[mark.milestone.state]}`} style={{ left: mark.x }} />
      ))}
      {preview.map((day) => (
        <span key={day} className="absolute bottom-0 top-5 w-0.5 -translate-x-1/2 bg-accent" style={{ left: x(day) }} />
      ))}
    </div>
  );
}

interface RowProps {
  row: PackageRow;
  axis: TimelineAxis;
  width: number;
  today: number;
  workPackages: GoalWorkPackage[];
  barHelpId: string;
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
  /** `trigger`: was geklickt wurde – die Karte steht dort und gibt ihm danach den Fokus zurück. */
  onOpenPackage: (id: string, trigger: HTMLElement) => void;
  onReschedule: (workPackage: GoalWorkPackage, range: DayRange | null) => void;
  announce: (text: string) => void;
}

/**
 * Eine Zeile im Band (c60bf914): links Name, Zeitraum, Stand und – eingeklappt – die TODOs ohne
 * Datum; rechts der Balken des Pakets, darunter nur bei Bedarf seine fälligen TODOs. Auf der Achse
 * abgelegt setzt ein TODO Tag und Paket, links abgelegt verliert es das Datum. Beim Ziehen nimmt die
 * Zeile nur an, was zu ihrem Projekt passt; die übrigen werden blass.
 */
export function PackageRowView({
  row, axis, width, today, workPackages, barHelpId, onPlan, onOpenTodo, onOpenPackage, onReschedule, announce,
}: RowProps) {
  const t = useT();
  const tt = t.goals.timeline;
  const tw = t.goals.workPackages;
  const { dragged, preview } = useDragState();
  const listId = useId();
  const [showUndated, setShowUndated] = useState(false);
  const id = rowDropId(row.band, row.key);
  const accepts = acceptsDrop(dragged, id);
  const { setNodeRef, isOver } = useDroppable({ id });
  const undatedId = undatedDropId(row.band, row.key);
  const undatedAccepts = acceptsDrop(dragged, undatedId);
  const { setNodeRef: setUndatedRef, isOver: undatedOver } = useDroppable({ id: undatedId });
  const { workPackage, range } = row;
  const chipsTop = range ? BAR_ZONE : ROW_PADDING;
  // Wohin ein gezogener Balken dieses Pakets käme: gestrichelt an seinem neuen Platz
  const ghost = workPackage && dragged?.kind === 'package' && dragged.workPackage.id === workPackage.id
    && (dragged.mode === 'move' || dragged.mode === 'schedule') && preview?.workPackageId === workPackage.id ? preview.range : null;
  const ghostBox = ghost ? barBox(axis, ghost, width) : null;

  return (
    <div className="flex border-t border-border" data-testid={`timeline-row-${row.band}-${row.key}`}>
      <div ref={setUndatedRef} data-testid={`timeline-undated-${row.band}-${row.key}`}
        data-accepts={undatedAccepts === null ? undefined : String(undatedAccepts)}
        className={`${LABEL_COLUMN} py-1 pl-3 pr-2 transition-colors ${dropTone(undatedAccepts, undatedOver)}`}>
        {workPackage ? (
          <>
            <button type="button" onClick={(event) => onOpenPackage(workPackage.id, event.currentTarget)} title={workPackage.title}
              aria-label={tw.card.replace('{title}', workPackage.title)}
              className="-mx-1 flex w-[calc(100%+0.5rem)] min-w-0 items-center gap-1.5 rounded px-1 text-left hover:bg-surface-hover focus-ring">
              <WorkPackageMark state={workPackage.state} />
              <span className="min-w-0 truncate text-[13px] font-medium leading-[18px] text-text">{workPackage.title}</span>
            </button>
            <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] leading-[14px] text-text-tertiary">
              <span className="tabular-nums" title={tw.progress.replace('{done}', String(workPackage.done))
                .replace('{total}', String(workPackage.total))}>
                {workPackage.done}/{workPackage.total}
              </span>
              <DecisionCounts openQuestions={workPackage.openQuestions} decided={workPackage.decided} />
              {row.undated.length > 0 && <UndatedToggle count={row.undated.length} open={showUndated} controls={listId} onToggle={setShowUndated} />}
            </div>
          </>
        ) : (
          <div className="flex flex-wrap items-center gap-x-2 text-[11px] leading-[18px] text-text-tertiary">
            <span className="min-w-0 truncate text-xs italic" title={tt.noPackage}>{tt.noPackage}</span>
            {row.undated.length > 0 && <UndatedToggle count={row.undated.length} open={showUndated} controls={listId} onToggle={setShowUndated} />}
          </div>
        )}
        {row.undated.length > 0 && (
          <ul id={listId} hidden={!showUndated} className="mb-1 mt-1 space-y-1">
            {showUndated && row.undated.map((todo) => (
              <li key={todo.id}>
                <TimelineChip todo={todo} workPackages={workPackages} today={today} compact onPlan={onPlan} onOpenTodo={onOpenTodo} />
              </li>
            ))}
          </ul>
        )}
      </div>
      <div ref={setNodeRef} data-accepts={accepts === null ? undefined : String(accepts)}
        className={`relative min-w-0 flex-1 transition-colors ${dropTone(accepts, isOver)}`}
        style={{ minHeight: chipsTop + row.lanes * LANE_HEIGHT + (row.lanes > 0 ? 3 : 0) }}>
        {workPackage && range && (
          <WorkPackageBar workPackage={workPackage} range={range} axis={axis} width={width} helpId={barHelpId}
            onOpen={(trigger) => onOpenPackage(workPackage.id, trigger)}
            onReschedule={(next) => onReschedule(workPackage, next)} announce={announce} />
        )}
        {workPackage && !range && (
          <span className="pointer-events-none absolute left-2 top-2 text-[11px] italic text-text-tertiary">{tw.noRange}</span>
        )}
        {ghostBox && (
          <span aria-hidden="true" data-testid="timeline-ghost"
            className="pointer-events-none absolute rounded-md border-2 border-dashed border-accent bg-accent/10"
            style={{ left: ghostBox.left, width: ghostBox.width, top: BAR_TOP, height: BAR_HEIGHT }} />
        )}
        {row.items.map((item) => (
          <TimelineChip key={item.todo.id} todo={item.todo} workPackages={workPackages} today={today} compact
            placement={{ left: item.left, top: chipsTop + item.lane * LANE_HEIGHT, flipped: item.flipped, anchor: item.anchor }}
            onPlan={onPlan} onOpenTodo={onOpenTodo} />
        ))}
      </div>
    </div>
  );
}

/** Zähler der TODOs ohne Datum an der Beschriftung – eingeklappt, bis jemand sie sehen will. */
export function UndatedToggle({ count, open, controls, onToggle }: {
  count: number;
  open: boolean;
  controls: string;
  onToggle: (open: boolean) => void;
}) {
  const tt = useT().goals.timeline;
  return (
    <button type="button" aria-expanded={open} aria-controls={controls} onClick={() => onToggle(!open)}
      className="inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap rounded hover:text-accent focus-ring">
      {tt.undatedTodos.replace('{n}', String(count))}
      <ChevronRight className={`h-3 w-3 transition-transform motion-reduce:transition-none ${open ? 'rotate-90' : ''}`} aria-hidden="true" />
    </button>
  );
}
