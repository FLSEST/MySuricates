import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type FormEvent, type RefObject } from 'react';
import { MoreHorizontal } from 'lucide-react';
import { useDraggable } from '@dnd-kit/core';
import { Button } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import {
  CHIP_ANCHOR, CHIP_WIDTH, dayOf, dayOfIsoDate, dueDateOfDay, isOverdue, isoDateOfDay, packageRange, type PlanChanges,
} from './timelineLayout';
import type { ChipDragData } from './timelineKeyboard';
import { STATUS_DOT, dueTitle } from './planDisplay';
import { packageOptions } from './planning';
import { rangeText } from './dragLabel';
import { TimelinePopover } from './TimelinePopover';
import { useDragState } from './timelineDrag';
import { DecisionCard, DecisionMark } from './DecisionMark';
import { useDecisionScope } from './decisionContext';
import type { GoalTodoItem, GoalWorkPackage } from '../../types';

/**
 * This is what a TODO looks like on the timeline: status dot and title, overdue red, done
 * struck through. The dot sits `CHIP_ANCHOR` px from the edge - exactly on the due day.
 */
export function ChipFace({ todo, overdue, flipped = false, lifted = false, compact = false }: {
  todo: GoalTodoItem;
  overdue: boolean;
  flipped?: boolean;
  /** The image under the mouse pointer while dragging. */
  lifted?: boolean;
  /** Narrow, in the „ohne Datum“ area of a row. */
  compact?: boolean;
}) {
  const done = todo.status === 'DONE';
  const text = done ? 'text-text-tertiary line-through' : overdue ? 'text-danger' : 'text-text';
  return (
    <span className={`flex w-full items-center gap-1.5 rounded-md border bg-surface-raised px-[7px] ${compact ? 'h-6 text-[11px]' : 'h-7 text-xs'} ${
      flipped ? 'flex-row-reverse' : ''} ${overdue ? 'border-danger/60' : 'border-border'} ${lifted ? 'shadow-lg ring-1 ring-accent' : 'shadow-sm'}`}>
      <span className={`h-2 w-2 shrink-0 rounded-full ${overdue ? 'bg-danger' : STATUS_DOT[todo.status]}`} />
      <span className={`min-w-0 flex-1 truncate ${flipped ? 'text-right' : 'text-left'} ${text}`}>{todo.title}</span>
      <DecisionMark todo={todo} size="sm" interactive={false} />
    </span>
  );
}

interface TimelineChipProps {
  todo: GoalTodoItem;
  /** All packages of the goal - the menu offers those of its own project. */
  workPackages: GoalWorkPackage[];
  today: number;
  /**
   * Place in the timeline; without it, the chip sits in the flow („ohne Datum“ area of a row). `anchor`:
   * distance from the left edge to the day on which the status dot sits.
   */
  placement?: { left: number; top: number; flipped: boolean; anchor: number };
  /** Narrow and as wide as its place - in the „ohne Datum“ area. */
  compact?: boolean;
  /** From the chip's menu - the way without dragging. */
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
}

/** For this long after release, a click still counts as the end of the drag, not as a click. */
const CLICK_AFTER_DRAG_MS = 300;

/**
 * A TODO on the timeline (TODO e192df18): draggable with the mouse or by keyboard (space, then
 * arrow keys - TODO ff6f08fd). A click (or Enter) opens the TODO dialog (c60bf914, Flo:
 * „anklickbar“); the menu with „Datum setzen“, „Arbeitspaket“ and „Entscheiden“ opens via ⋯ next to it or
 * the right mouse button. A click right after dragging does not count.
 */
export function TimelineChip({ todo, workPackages, today, placement, compact = false, onPlan, onOpenTodo }: TimelineChipProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [open, setOpen] = useState(false);
  const [deciding, setDeciding] = useState(false);
  const scope = useDecisionScope();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dragData: ChipDragData = {
    anchor: placement?.anchor ?? CHIP_ANCHOR, flipped: placement?.flipped ?? false, kind: 'todo', ...(compact ? { compact } : {}),
  };
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `todo:${todo.id}`, data: dragData });
  const { dragged } = useDragState();
  const draggingMe = isDragging || (dragged?.kind === 'todo' && dragged.todo.id === todo.id);
  const dragEndedAt = useRef(0);
  const wasDragging = useRef(false);
  useEffect(() => {
    if (wasDragging.current && !draggingMe) dragEndedAt.current = Date.now();
    wasDragging.current = draggingMe;
  }, [draggingMe]);
  const menuRef = useRef<HTMLButtonElement>(null);
  const close = useCallback(() => setOpen(false), []);
  const overdue = isOverdue(todo, today);
  const workPackage = workPackages.find((item) => item.id === todo.workPackageId);
  const due = todo.dueDate ? dueTitle(todo.dueDate, overdue, locale, t) : t.goals.timeline.unscheduledTodo;
  const tooltip = [todo.title, due, workPackage && t.goals.workPackages.chip.replace('{title}', workPackage.title)]
    .filter(Boolean).join('\n');
  const style: CSSProperties = placement
    ? { position: 'absolute', left: placement.left, top: placement.top, width: CHIP_WIDTH }
    : { width: compact ? '100%' : CHIP_WIDTH };

  return (
    <div ref={setNodeRef} style={style} data-todo-id={todo.id}
      className={`group ${placement ? '' : 'relative'} ${isDragging ? 'opacity-40' : ''}`}>
      <button
        ref={triggerRef}
        type="button"
        {...attributes}
        {...listeners}
        aria-label={`${todo.title} – ${due}`}
        aria-haspopup="dialog"
        data-overdue={overdue ? 'true' : undefined}
        data-done={todo.status === 'DONE' ? 'true' : undefined}
        title={tooltip}
        onClick={() => {
          if (draggingMe || Date.now() - dragEndedAt.current < CLICK_AFTER_DRAG_MS) return;
          onOpenTodo(todo.id);
        }}
        onContextMenu={(event) => {
          event.preventDefault();
          setOpen(true);
        }}
        className="block w-full cursor-grab rounded-md focus-ring active:cursor-grabbing"
        style={{ touchAction: 'none' }}
      >
        <ChipFace todo={todo} overdue={overdue} flipped={placement?.flipped} compact={compact} />
      </button>
      <button ref={menuRef} type="button" aria-haspopup="dialog" aria-expanded={open}
        aria-label={t.goals.timeline.planMenu.replace('{title}', todo.title)} title={t.goals.timeline.planMenu.replace('{title}', todo.title)}
        onClick={() => setOpen((value) => !value)} data-testid={`timeline-chip-menu-${todo.id}`}
        className={`absolute top-1/2 -translate-y-1/2 ${placement?.flipped ? 'left-0.5' : 'right-0.5'} rounded bg-surface-raised p-0.5 text-text-tertiary opacity-0 hover:text-accent focus:opacity-100 focus-ring group-hover:opacity-100 max-sm:opacity-100 ${
          open ? 'opacity-100' : ''}`}>
        <MoreHorizontal className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      {open && (
        <ChipMenu
          todo={todo}
          workPackages={workPackages}
          alignRight={placement?.flipped ?? false}
          triggerRef={menuRef}
          onClose={close}
          onPlan={(changes) => {
            close();
            onPlan(todo.id, changes);
          }}
          onOpenTodo={() => {
            close();
            onOpenTodo(todo.id);
          }}
          onDecide={scope?.enabled && (todo.decisionState === 'DECIDED' || (todo.openQuestion && todo.status !== 'DONE')) ? () => {
            close();
            setDeciding(true);
          } : undefined}
        />
      )}
      {deciding && scope && (
        <DecisionCard todo={todo} goalId={scope.goalId} triggerRef={triggerRef} onClose={() => setDeciding(false)} />
      )}
    </div>
  );
}

function ChipMenu({ todo, workPackages, alignRight, triggerRef, onClose, onPlan, onOpenTodo, onDecide }: {
  todo: GoalTodoItem;
  workPackages: GoalWorkPackage[];
  alignRight: boolean;
  triggerRef: RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  onPlan: (changes: PlanChanges) => void;
  onOpenTodo: () => void;
  /** Decide an open question or change the decision (68a5e24e); absent without a question and decision. */
  onDecide?: () => void;
}) {
  const t = useT();
  const tt = t.goals.timeline;
  const locale = useI18nStore((s) => s.locale);
  const dateId = useId();
  const packageId = useId();
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
      align={alignRight ? 'end' : 'start'}>
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
        <label htmlFor={packageId} className="block text-xs font-medium text-text-secondary">{tt.workPackage}</label>
        <select id={packageId} value={todo.workPackageId ?? ''} onChange={(event) => onPlan({ workPackageId: event.target.value })}
          className="input-base w-full py-1 text-sm">
          <option value="">{tt.noWorkPackageOption}</option>
          {/* Only packages of its own project (c60bf914) - and the current one, even if it does not fit */}
          {packageOptions(workPackages, todo.projectId, todo.workPackageId ?? '').map((item) => {
            const range = packageRange(item);
            return (
              <option key={item.id} value={item.id}>
                {`${item.title} · ${range ? rangeText(range, locale) : t.goals.workPackages.noRange}`}
              </option>
            );
          })}
        </select>
      </div>
      <div className="mt-3 flex justify-end gap-1 border-t border-border pt-2">
        {onDecide && (
          <Button type="button" variant="ghost" size="sm" onClick={onDecide}>
            {todo.decisionState === 'DECIDED' ? t.goals.decision.change : t.goals.decision.decide}
          </Button>
        )}
        <Button type="button" variant="ghost" size="sm" onClick={onOpenTodo}>{tt.edit}</Button>
      </div>
    </TimelinePopover>
  );
}
