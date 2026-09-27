import { useCallback, useId, useRef, useState, type CSSProperties, type FormEvent, type RefObject } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { Button } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { CHIP_ANCHOR, CHIP_WIDTH, dayOf, dayOfIsoDate, dueDateOfDay, isOverdue, isoDateOfDay, type PlanChanges } from './timelineLayout';
import type { ChipDragData } from './timelineKeyboard';
import { SHORT_DATE, STATUS_DOT, dueTitle } from './planDisplay';
import { TimelinePopover } from './TimelinePopover';
import type { GoalMilestone, GoalTodoItem } from '../../types';

/**
 * So sieht ein TODO auf dem Zeitstrahl aus: Statuspunkt und Titel, überfällig rot, erledigt
 * durchgestrichen. Der Punkt sitzt `CHIP_ANCHOR` px vom Rand – genau auf dem Fälligkeitstag.
 */
export function ChipFace({ todo, overdue, flipped = false, lifted = false }: {
  todo: GoalTodoItem;
  overdue: boolean;
  flipped?: boolean;
  /** Das Bild unter dem Mauszeiger beim Ziehen. */
  lifted?: boolean;
}) {
  const done = todo.status === 'DONE';
  const text = done ? 'text-text-tertiary line-through' : overdue ? 'text-danger' : 'text-text';
  return (
    <span className={`flex h-7 w-full items-center gap-1.5 rounded-md border bg-surface-raised px-[7px] text-xs ${
      flipped ? 'flex-row-reverse' : ''} ${overdue ? 'border-danger/60' : 'border-border'} ${lifted ? 'shadow-lg ring-1 ring-accent' : 'shadow-sm'}`}>
      <span className={`h-2 w-2 shrink-0 rounded-full ${overdue ? 'bg-danger' : STATUS_DOT[todo.status]}`} />
      <span className={`min-w-0 flex-1 truncate ${flipped ? 'text-right' : 'text-left'} ${text}`}>{todo.title}</span>
    </span>
  );
}

interface TimelineChipProps {
  todo: GoalTodoItem;
  milestones: GoalMilestone[];
  today: number;
  /**
   * Platz im Zeitstrahl; ohne steht der Chip im Fluss (Leiste „ungeplant“). `anchor`: Abstand vom
   * linken Rand zum Tag, auf dem der Statuspunkt sitzt.
   */
  placement?: { left: number; top: number; flipped: boolean; anchor: number };
  /** Aus dem Menü des Chips – dem Weg ohne Ziehen. */
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
}

/**
 * Ein TODO auf dem Zeitstrahl (TODO e192df18): mit der Maus oder per Tastatur (Leertaste, dann
 * Pfeiltasten – TODO ff6f08fd) ziehbar, ein Klick (oder Enter) öffnet sein Menü mit „Datum setzen“
 * und „Meilenstein“.
 */
export function TimelineChip({ todo, milestones, today, placement, onPlan, onOpenTodo }: TimelineChipProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dragData: ChipDragData = { anchor: placement?.anchor ?? CHIP_ANCHOR, flipped: placement?.flipped ?? false, kind: 'todo' };
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `todo:${todo.id}`, data: dragData });
  const close = useCallback(() => setOpen(false), []);
  const overdue = isOverdue(todo, today);
  const milestone = milestones.find((m) => m.id === todo.milestoneId);
  const due = todo.dueDate ? dueTitle(todo.dueDate, overdue, locale, t) : t.goals.timeline.unscheduled;
  const tooltip = [todo.title, due, milestone && t.goals.milestones.chip.replace('{title}', milestone.title)]
    .filter(Boolean).join('\n');
  const style: CSSProperties = placement
    ? { position: 'absolute', left: placement.left, top: placement.top, width: CHIP_WIDTH }
    : { width: CHIP_WIDTH };

  return (
    <div ref={setNodeRef} style={style} className={`${placement ? '' : 'relative'} ${isDragging ? 'opacity-40' : ''}`}>
      <button
        ref={triggerRef}
        type="button"
        {...attributes}
        {...listeners}
        aria-label={`${todo.title} – ${due}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        data-overdue={overdue ? 'true' : undefined}
        data-done={todo.status === 'DONE' ? 'true' : undefined}
        title={tooltip}
        onClick={() => setOpen((value) => !value)}
        className="block w-full cursor-grab rounded-md focus-ring active:cursor-grabbing"
        style={{ touchAction: 'none' }}
      >
        <ChipFace todo={todo} overdue={overdue} flipped={placement?.flipped} />
      </button>
      {open && (
        <ChipMenu
          todo={todo}
          milestones={milestones}
          alignRight={placement?.flipped ?? false}
          triggerRef={triggerRef}
          onClose={close}
          onPlan={(changes) => {
            close();
            onPlan(todo.id, changes);
          }}
          onOpenTodo={() => {
            close();
            onOpenTodo(todo.id);
          }}
        />
      )}
    </div>
  );
}

function ChipMenu({ todo, milestones, alignRight, triggerRef, onClose, onPlan, onOpenTodo }: {
  todo: GoalTodoItem;
  milestones: GoalMilestone[];
  alignRight: boolean;
  triggerRef: RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  onPlan: (changes: PlanChanges) => void;
  onOpenTodo: () => void;
}) {
  const t = useT();
  const tt = t.goals.timeline;
  const locale = useI18nStore((s) => s.locale);
  const dateId = useId();
  const milestoneId = useId();
  const [date, setDate] = useState(todo.dueDate ? isoDateOfDay(dayOf(todo.dueDate)) : '');
  const day = dayOfIsoDate(date);

  const applyDate = (event: FormEvent) => {
    event.preventDefault();
    if (day === null) return;
    if (todo.dueDate && dayOf(todo.dueDate) === day) onClose();
    else onPlan({ dueDate: dueDateOfDay(day) });
  };

  return (
    <TimelinePopover label={tt.planMenu.replace('{title}', todo.title)} onClose={onClose} triggerRef={triggerRef}
      className={`top-full mt-1 ${alignRight ? 'right-0' : 'left-0'}`}>
      <form onSubmit={applyDate} className="space-y-1.5">
        <label htmlFor={dateId} className="block text-xs font-medium text-text-secondary">{tt.setDate}</label>
        <div className="flex gap-2">
          <input id={dateId} type="date" value={date} onChange={(event) => setDate(event.target.value)}
            className="input-base min-w-0 flex-1 py-1 text-sm" />
          <Button type="submit" size="sm" disabled={day === null}>{tt.apply}</Button>
        </div>
        {todo.dueDate && (
          <button type="button" onClick={() => onPlan({ dueDate: '' })} className="text-xs text-text-secondary hover:text-danger">
            {tt.clearDate}
          </button>
        )}
      </form>
      <div className="mt-3 space-y-1.5">
        <label htmlFor={milestoneId} className="block text-xs font-medium text-text-secondary">{tt.milestone}</label>
        <select id={milestoneId} value={todo.milestoneId ?? ''} onChange={(event) => onPlan({ milestoneId: event.target.value })}
          className="input-base w-full py-1 text-sm">
          <option value="">{tt.noMilestoneOption}</option>
          {/* Nur Teilziele des eigenen Projekts (c60bf914) – und das bisherige, auch wenn es nicht passt */}
          {milestones.filter((m) => (m.projectId ?? null) === (todo.projectId ?? null) || m.id === todo.milestoneId).map((m) => (
            <option key={m.id} value={m.id}>
              {`${m.title} · ${m.dueDate ? formatDate(m.dueDate, locale, SHORT_DATE) : t.goals.milestones.noDate}`}
            </option>
          ))}
        </select>
      </div>
      <div className="mt-3 flex justify-end border-t border-border pt-2">
        <Button type="button" variant="ghost" size="sm" onClick={onOpenTodo}>{tt.edit}</Button>
      </div>
    </TimelinePopover>
  );
}
