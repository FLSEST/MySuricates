import { useId, useState } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import {
  LANE_HEIGHT, dayOf, rowDropId, undatedDropId, xOfDay, type PlanChanges, type TimelineAxis, type TimelineRow,
} from './timelineLayout';
import { acceptsDrop, dropTone, useDragged } from './timelineDrag';
import { SHORT_DATE } from './planDisplay';
import { MilestoneDiamond } from './MilestoneDiamond';
import { TimelineChip } from './TimelineChip';
import type { GoalMilestone, GoalTodoItem, MilestoneState } from '../../types';

/** Abstand der Chips zum oberen und unteren Rand einer Zeile. */
const ROW_PADDING = 8;
/** So viele TODOs ohne Datum zeigt eine Zeile, der Rest klappt hinter „+n“ auf. */
const UNDATED_SHOWN = 3;

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
  /** `trigger`: was geklickt wurde – die Karte steht dort und gibt ihm danach den Fokus zurück. */
  onOpenMilestone: (id: string, trigger: HTMLElement) => void;
}

/**
 * Eine Zeile: ein Teilziel (oder „ohne Teilziel“) mit seinen TODOs – die fälligen auf der Achse, die
 * ohne Datum links unter dem Namen, damit sie ihre Zuordnung behalten. Ablegen auf der Achse setzt
 * Tag und Teilziel, links nimmt es das Datum weg. Beim Ziehen nimmt sie nur an, was zu ihrem Projekt
 * passt (c60bf914).
 */
export function TimelineRowView({ row, axis, width, today, milestones, onPlan, onOpenTodo, onOpenMilestone }: RowProps) {
  const t = useT();
  const dragged = useDragged();
  const id = rowDropId(row.band, row.key);
  const accepts = acceptsDrop(dragged, id);
  const { setNodeRef, isOver } = useDroppable({ id, disabled: accepts === false });
  const undatedId = undatedDropId(row.band, row.key);
  const undatedAccepts = acceptsDrop(dragged, undatedId);
  const { setNodeRef: setUndatedRef, isOver: undatedOver } = useDroppable({ id: undatedId, disabled: undatedAccepts === false });
  const { milestone } = row;
  return (
    <div className="flex border-t border-border" data-testid={`timeline-row-${row.band}-${row.key}`}>
      <div ref={setUndatedRef} data-testid={`timeline-undated-${row.band}-${row.key}`}
        data-accepts={undatedAccepts === null ? undefined : String(undatedAccepts)}
        className={`${LABEL_COLUMN} space-y-1.5 py-2 pl-3 pr-2 transition-colors ${dropTone(undatedAccepts, undatedOver)}`}>
        {milestone
          ? <MilestoneRowLabel milestone={milestone} onOpen={(trigger) => onOpenMilestone(milestone.id, trigger)} />
          : <span className="block text-xs italic text-text-tertiary">{t.goals.timeline.noMilestone}</span>}
        {row.undated.length > 0 && (
          <UndatedTodos todos={row.undated} milestones={milestones} today={today} onPlan={onPlan} onOpenTodo={onOpenTodo} />
        )}
      </div>
      <div ref={setNodeRef} data-accepts={accepts === null ? undefined : String(accepts)}
        className={`relative min-w-0 flex-1 transition-colors ${dropTone(accepts, isOver)}`}
        style={{ minHeight: row.lanes * LANE_HEIGHT + 2 * ROW_PADDING }}>
        {milestone?.dueDate && (
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

function MilestoneRowLabel({ milestone, onOpen }: { milestone: GoalMilestone; onOpen: (trigger: HTMLElement) => void }) {
  const tm = useT().goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  return (
    <button type="button" onClick={(event) => onOpen(event.currentTarget)} className="-mx-1 block w-full rounded px-1 text-left hover:bg-surface-hover focus-ring">
      <span className="flex min-w-0 items-center gap-1.5">
        <MilestoneDiamond state={milestone.state} />
        <span className="truncate text-sm font-medium text-text" title={milestone.title}>{milestone.title}</span>
      </span>
      <span className="block truncate text-[11px] text-text-tertiary">
        {milestone.dueDate ? formatDate(milestone.dueDate, locale, SHORT_DATE) : tm.noDate} · {milestone.done}/{milestone.total} · {tm.states[milestone.state]}
        {milestone.reopened && ` · ${tm.reopened}`}
      </span>
    </button>
  );
}

/**
 * TODOs ohne Datum einer Zeile (c60bf914): die ersten drei als schmale Chips, der Rest hinter „+n“
 * aufklappbar. Ein Chip von hier auf die Achse gezogen bekommt ein Datum, wie jeder andere.
 */
function UndatedTodos({ todos, milestones, today, onPlan, onOpenTodo }: {
  todos: GoalTodoItem[];
  milestones: GoalMilestone[];
  today: number;
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
}) {
  const tt = useT().goals.timeline;
  const listId = useId();
  const [all, setAll] = useState(false);
  const hidden = todos.length - UNDATED_SHOWN;
  const shown = all || hidden <= 0 ? todos : todos.slice(0, UNDATED_SHOWN);
  return (
    <div className="space-y-1">
      <p className="text-[10px] font-medium text-text-tertiary">{tt.undatedTodos.replace('{n}', String(todos.length))}</p>
      <ul id={listId} className="space-y-1">
        {shown.map((todo) => (
          <li key={todo.id}>
            <TimelineChip todo={todo} milestones={milestones} today={today} compact onPlan={onPlan} onOpenTodo={onOpenTodo} />
          </li>
        ))}
      </ul>
      {hidden > 0 && (
        <button type="button" aria-expanded={all} aria-controls={listId} onClick={() => setAll((value) => !value)}
          className="rounded px-1 text-[11px] font-medium text-text-secondary hover:text-accent focus-ring">
          {all ? tt.fewerTodos : tt.moreTodos.replace('{n}', String(hidden))}
        </button>
      )}
    </div>
  );
}
