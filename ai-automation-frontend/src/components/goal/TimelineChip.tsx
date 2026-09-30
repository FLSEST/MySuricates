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
 * So sieht ein TODO auf dem Zeitstrahl aus: Statuspunkt und Titel, überfällig rot, erledigt
 * durchgestrichen. Der Punkt sitzt `CHIP_ANCHOR` px vom Rand – genau auf dem Fälligkeitstag.
 */
export function ChipFace({ todo, overdue, flipped = false, lifted = false, compact = false }: {
  todo: GoalTodoItem;
  overdue: boolean;
  flipped?: boolean;
  /** Das Bild unter dem Mauszeiger beim Ziehen. */
  lifted?: boolean;
  /** Schmal im Bereich „ohne Datum“ einer Zeile. */
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
  /** Alle Pakete des Ziels – das Menü bietet die des eigenen Projekts an. */
  workPackages: GoalWorkPackage[];
  today: number;
  /**
   * Platz im Zeitstrahl; ohne steht der Chip im Fluss (Bereich „ohne Datum“ einer Zeile). `anchor`:
   * Abstand vom linken Rand zum Tag, auf dem der Statuspunkt sitzt.
   */
  placement?: { left: number; top: number; flipped: boolean; anchor: number };
  /** Schmal und so breit wie sein Platz – im Bereich „ohne Datum“. */
  compact?: boolean;
  /** Aus dem Menü des Chips – dem Weg ohne Ziehen. */
  onPlan: (todoId: string, changes: PlanChanges) => void;
  onOpenTodo: (todoId: string) => void;
}

/** So lange nach dem Loslassen gilt ein Klick noch als Ende des Ziehens, nicht als Klick. */
const CLICK_AFTER_DRAG_MS = 300;

/**
 * Ein TODO auf dem Zeitstrahl (TODO e192df18): mit der Maus oder per Tastatur (Leertaste, dann
 * Pfeiltasten – TODO ff6f08fd) ziehbar. Ein Klick (oder Enter) öffnet den TODO-Dialog (c60bf914, Flo:
 * „anklickbar“); das Menü mit „Datum setzen“, „Arbeitspaket“ und „Entscheiden“ öffnet ⋯ daneben oder
 * die rechte Maustaste. Ein Klick direkt nach dem Ziehen zählt nicht.
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
  /** Offene Frage entscheiden bzw. die Entscheidung ändern (68a5e24e); fehlt ohne Frage und Entscheidung. */
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
          {/* Nur Pakete des eigenen Projekts (c60bf914) – und das bisherige, auch wenn es nicht passt */}
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
