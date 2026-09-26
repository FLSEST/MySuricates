import { useId } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { LANE_HEIGHT, dayOf, rowDropId, xOfDay, type PlanChanges, type TimelineAxis, type TimelineRow } from './timelineLayout';
import { SHORT_DATE } from './planDisplay';
import { MilestoneDiamond } from './MilestoneDiamond';
import { TimelineChip } from './TimelineChip';
import type { GoalMilestone, GoalTodoItem, MilestoneState } from '../../types';

/** Abstand der Chips zum oberen und unteren Rand einer Zeile. */
const ROW_PADDING = 8;

/** Breite der Beschriftungsspalte – Zeilen, Achse und Linien müssen dieselbe nehmen. */
export const LABEL_COLUMN = 'w-28 shrink-0 sm:w-44';
const LINES_LEFT = 'left-28 sm:left-44';

const MILESTONE_LINE: Record<MilestoneState, string> = {
  OPEN: 'border-border-strong',
  DUE_SOON: 'border-warning/70',
  OVERDUE: 'border-danger/70',
  REACHED: 'border-success/70',
};

/**
 * Senkrechte Linien über Achse und Zeilen: Raster, heute, das Zieldatum und beim Ziehen der Tag,
 * auf dem das TODO landen würde. Liegt unter den Chips und nimmt keine Klicks an.
 */
export function TimelineLines({ axis, width, targetDay, preview }: {
  axis: TimelineAxis;
  width: number;
  targetDay: number | null;
  preview: number | null;
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
      {preview !== null && <span className="absolute bottom-0 top-5 w-0.5 -translate-x-1/2 bg-accent" style={{ left: x(preview) }} />}
    </div>
  );
}

interface RowProps {
  row: TimelineRow;
  axis: TimelineAxis;
  width: number;
  today: number;
  milestones: GoalMilestone[];
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
  onOpenMilestone: (id: string) => void;
}

/** Eine Zeile: ein Meilenstein (oder „ohne Meilenstein“) mit seinen fälligen TODOs; Ablegen setzt Tag und Zeile. */
export function TimelineRowView({ row, axis, width, today, milestones, onPlan, onOpenTodo, onOpenMilestone }: RowProps) {
  const t = useT();
  const { setNodeRef, isOver } = useDroppable({ id: rowDropId(row.key) });
  const { milestone } = row;
  return (
    <div className="flex border-t border-border" data-testid={`timeline-row-${row.key}`}>
      <div className={`${LABEL_COLUMN} py-2 pr-2`}>
        {milestone
          ? <MilestoneRowLabel milestone={milestone} onOpen={() => onOpenMilestone(milestone.id)} />
          : <span className="text-xs text-text-tertiary">{t.goals.timeline.noMilestone}</span>}
      </div>
      <div ref={setNodeRef} className={`relative min-w-0 flex-1 transition-colors ${isOver ? 'bg-accent/10' : ''}`}
        style={{ height: row.lanes * LANE_HEIGHT + 2 * ROW_PADDING }}>
        {milestone && (
          <span aria-hidden="true" className={`absolute inset-y-0 border-l-2 border-dashed ${MILESTONE_LINE[milestone.state]}`}
            style={{ left: xOfDay(axis, dayOf(milestone.dueDate), width) - 1 }} />
        )}
        {row.items.map((item) => (
          <TimelineChip key={item.todo.id} todo={item.todo} milestones={milestones} today={today}
            placement={{ left: item.left, top: ROW_PADDING + item.lane * LANE_HEIGHT, flipped: item.flipped, anchor: item.anchor }}
            onPlan={onPlan} onOpenTodo={onOpenTodo} />
        ))}
      </div>
    </div>
  );
}

function MilestoneRowLabel({ milestone, onOpen }: { milestone: GoalMilestone; onOpen: () => void }) {
  const tm = useT().goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  return (
    <button type="button" onClick={onOpen} className="-mx-1 block w-full rounded px-1 text-left hover:bg-surface-hover focus-ring">
      <span className="flex min-w-0 items-center gap-1.5">
        <MilestoneDiamond state={milestone.state} />
        <span className="truncate text-sm font-medium text-text">{milestone.title}</span>
      </span>
      <span className="block truncate text-[11px] text-text-tertiary">
        {formatDate(milestone.dueDate, locale, SHORT_DATE)} · {milestone.done}/{milestone.total} · {tm.states[milestone.state]}
        {milestone.reopened && ` · ${tm.reopened}`}
      </span>
    </button>
  );
}

/** Leiste „ungeplant“: alle TODOs ohne Fälligkeit; hierher gezogen verliert ein TODO sein Datum. */
export function UnplannedBar({ todos, milestones, today, onPlan, onOpenTodo }: {
  todos: GoalTodoItem[];
  milestones: GoalMilestone[];
  today: number;
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
}) {
  const tt = useT().goals.timeline;
  const headingId = useId();
  const { setNodeRef, isOver } = useDroppable({ id: 'unplanned' });
  return (
    <section ref={setNodeRef} aria-labelledby={headingId}
      className={`rounded-xl border border-dashed p-3 transition-colors ${isOver ? 'border-accent bg-accent/5' : 'border-border-strong'}`}>
      <div className="mb-2 flex flex-wrap items-baseline gap-x-2">
        <h3 id={headingId} className="text-xs font-semibold uppercase tracking-wide text-text-secondary">{tt.unplanned}</h3>
        <span className="text-xs tabular-nums text-text-tertiary">{todos.length}</span>
        <span className="text-xs text-text-tertiary">{tt.unplannedHint}</span>
      </div>
      {todos.length === 0 ? (
        <p className="text-xs text-text-tertiary">{tt.unplannedEmpty}</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {todos.map((todo) => (
            <TimelineChip key={todo.id} todo={todo} milestones={milestones} today={today} onPlan={onPlan} onOpenTodo={onOpenTodo} />
          ))}
        </div>
      )}
    </section>
  );
}
