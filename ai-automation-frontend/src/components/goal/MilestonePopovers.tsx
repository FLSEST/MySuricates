import { useState, type CSSProperties, type FormEvent, type RefObject } from 'react';
import { Button, Input } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { useCreateMilestone, useUpdateMilestone } from '../../hooks/useGoalTimeline';
import { dayOf, dayOfIsoDate, dueDateOfDay, isoDateOfDay } from './timelineLayout';
import { MilestoneDiamond } from './MilestoneDiamond';
import { TimelinePopover } from './TimelinePopover';
import type { GoalMilestone, GoalTodoItem, UpdateGoalMilestoneRequest } from '../../types';

interface Placement {
  className?: string;
  style?: CSSProperties;
  triggerRef?: RefObject<HTMLElement | null>;
}

/** Titel höchstens so lang, wie das Backend annimmt. */
const MAX_TITLE = 200;

/**
 * Meilenstein anlegen oder bearbeiten (TODO e192df18). Das Datum gilt wie eine Fälligkeit bis
 * 23:59:59 Ortszeit; beim Bearbeiten geht nur, was sich geändert hat, an den Server.
 */
export function MilestoneForm({ goalId, milestone, initialDay, onClose, ...placement }: {
  goalId: string;
  milestone?: GoalMilestone;
  initialDay: number | null;
  onClose: () => void;
} & Placement) {
  const tm = useT().goals.milestones;
  const [title, setTitle] = useState(milestone?.title ?? '');
  const [date, setDate] = useState(
    milestone ? isoDateOfDay(dayOf(milestone.dueDate)) : initialDay !== null ? isoDateOfDay(initialDay) : '',
  );
  const create = useCreateMilestone(goalId);
  const update = useUpdateMilestone(goalId);
  const day = dayOfIsoDate(date);
  const trimmed = title.trim();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!trimmed || day === null) return;
    if (!milestone) {
      create.mutate({ title: trimmed, dueDate: dueDateOfDay(day) }, { onSuccess: onClose });
      return;
    }
    const data: UpdateGoalMilestoneRequest = {};
    if (trimmed !== milestone.title) data.title = trimmed;
    if (day !== dayOf(milestone.dueDate)) data.dueDate = dueDateOfDay(day);
    if (Object.keys(data).length === 0) onClose();
    else update.mutate({ id: milestone.id, data }, { onSuccess: onClose });
  };

  return (
    <TimelinePopover label={milestone ? tm.edit : tm.create} onClose={onClose} {...placement}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm font-semibold text-text">{milestone ? tm.edit : tm.create}</p>
        <Input label={tm.titleLabel} value={title} maxLength={MAX_TITLE} onChange={(event) => setTitle(event.target.value)} />
        <Input label={tm.dateLabel} type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" size="sm" onClick={onClose}>{tm.cancel}</Button>
          <Button type="submit" size="sm" loading={create.isPending || update.isPending} disabled={!trimmed || day === null}>
            {tm.save}
          </Button>
        </div>
      </form>
    </TimelinePopover>
  );
}

/**
 * Kleine Karte einer Raute: Zustand, Fortschritt, die zugeordneten TODOs, Bearbeiten und Löschen.
 * Löschen fragt der Zeitstrahl in seinem eigenen Dialog nach (23635ef6) – nicht in der Karte, die
 * ein Klick daneben schließt.
 */
export function MilestoneCard({ milestone, todos, onEdit, onDelete, onClose, ...placement }: {
  milestone: GoalMilestone;
  todos: GoalTodoItem[];
  onEdit: () => void;
  onDelete: () => void;
  onClose: () => void;
} & Placement) {
  const t = useT();
  const tm = t.goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const assigned = todos.filter((todo) => todo.milestoneId === milestone.id && todo.status !== 'ARCHIVED');

  return (
    <TimelinePopover label={tm.card.replace('{title}', milestone.title)} onClose={onClose} {...placement}>
      <div className="flex items-start gap-2">
        <span className="pt-1.5"><MilestoneDiamond state={milestone.state} /></span>
        <div className="min-w-0">
          <p className="break-words text-sm font-semibold text-text">{milestone.title}</p>
          <p className="text-xs text-text-secondary">
            {formatDate(milestone.dueDate, locale)} · {tm.states[milestone.state]}
            {milestone.reopened && <span className="text-warning"> · {tm.reopened}</span>}
          </p>
        </div>
      </div>
      <p className="mt-2 text-xs text-text-secondary">
        {tm.progress.replace('{done}', String(milestone.done)).replace('{total}', String(milestone.total))}
      </p>
      <p className="mt-2 text-xs font-medium text-text-secondary">{tm.todos}</p>
      {assigned.length === 0 ? (
        <p className="text-xs text-text-tertiary">{tm.noTodos}</p>
      ) : (
        <ul className="mt-1 max-h-40 space-y-0.5 overflow-y-auto text-xs">
          {assigned.map((todo) => (
            <li key={todo.id} className={`truncate ${todo.status === 'DONE' ? 'text-text-tertiary line-through' : 'text-text'}`}>
              {todo.title}
            </li>
          ))}
        </ul>
      )}
      <div className="mt-3 flex justify-end gap-2 border-t border-border pt-2">
        <button type="button" onClick={onDelete} className="btn-base px-3 py-1.5 text-xs text-danger hover:bg-danger-subtle">
          {tm.delete}
        </button>
        {/* Den Fokus bekommt Bearbeiten, nicht das Löschen daneben. */}
        <Button type="button" variant="secondary" size="sm" onClick={onEdit} data-autofocus>{tm.editAction}</Button>
      </div>
    </TimelinePopover>
  );
}
