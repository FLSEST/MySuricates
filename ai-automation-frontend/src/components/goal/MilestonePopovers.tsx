import { useState, type CSSProperties, type FormEvent, type RefObject } from 'react';
import { Button, Input, Select } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { useCreateMilestone, useUpdateMilestone } from '../../hooks/useGoalTimeline';
import { MAX_SUBGOAL_TITLE, createRequest, draftOf, isDraftValid, projectLocked, updateRequest } from './subgoalForm';
import { MilestoneDiamond } from './MilestoneDiamond';
import { TimelinePopover } from './TimelinePopover';
import type { GoalMilestone, GoalProject, GoalTodoItem } from '../../types';

interface Placement {
  className?: string;
  style?: CSSProperties;
  triggerRef?: RefObject<HTMLElement | null>;
}

/**
 * Teilziel anlegen oder bearbeiten (TODO e192df18, c60bf914): Titel, Projekt (vorbelegt aus dem
 * Band), Datum optional. Beim Bearbeiten geht nur, was sich geändert hat, an den Server.
 */
export function MilestoneForm({ goalId, projects, milestone, initialDay, initialProjectId, onClose, ...placement }: {
  goalId: string;
  projects: GoalProject[];
  milestone?: GoalMilestone;
  initialDay: number | null;
  initialProjectId?: string | null;
  onClose: () => void;
} & Placement) {
  const tm = useT().goals.milestones;
  const [draft, setDraft] = useState(() => draftOf(milestone, {
    projectId: initialProjectId ?? projects[0]?.projectId ?? '', day: initialDay,
  }));
  const create = useCreateMilestone(goalId);
  const update = useUpdateMilestone(goalId);
  const options = projects.map((project) => ({ value: project.projectId, label: project.name }));
  if (draft.projectId && !options.some((option) => option.value === draft.projectId)) {
    options.push({ value: draft.projectId, label: draft.projectId });
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!milestone) {
      const request = createRequest(draft);
      if (request) create.mutate(request, { onSuccess: onClose });
      return;
    }
    const data = updateRequest(milestone, draft);
    if (!data) return;
    if (Object.keys(data).length === 0) onClose();
    else update.mutate({ id: milestone.id, data }, { onSuccess: onClose });
  };

  return (
    <TimelinePopover label={milestone ? tm.edit : tm.create} onClose={onClose} {...placement}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm font-semibold text-text">{milestone ? tm.edit : tm.create}</p>
        <Input label={tm.titleLabel} value={draft.title} maxLength={MAX_SUBGOAL_TITLE}
          onChange={(event) => setDraft({ ...draft, title: event.target.value })} />
        {options.length === 0
          ? <p className="text-xs text-text-secondary">{tm.noProjects}</p>
          : (
            <Select label={tm.project} aria-label={tm.project} value={draft.projectId} options={options}
              disabled={projectLocked(milestone)} title={projectLocked(milestone) ? tm.projectLocked : undefined}
              onChange={(event) => setDraft({ ...draft, projectId: event.target.value })} />
          )}
        <Input label={tm.dateOptional} type="date" value={draft.date} onChange={(event) => setDraft({ ...draft, date: event.target.value })} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" size="sm" onClick={onClose}>{tm.cancel}</Button>
          <Button type="submit" size="sm" loading={create.isPending || update.isPending} disabled={!isDraftValid(draft)}>
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
            {milestone.dueDate ? formatDate(milestone.dueDate, locale) : tm.noDate} · {tm.states[milestone.state]}
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
            <li key={todo.id} title={todo.title} className={`truncate ${todo.status === 'DONE' ? 'text-text-tertiary line-through' : 'text-text'}`}>
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
