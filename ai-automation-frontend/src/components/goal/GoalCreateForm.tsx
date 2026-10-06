import { useId, useState } from 'react';
import { Button, Card, Input, Select } from '../ui';
import { useCreateGoal } from '../../hooks/useGoals';
import { useProjects } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import type { Goal } from '../../types';

/**
 * A new goal with title, target date, „fertig heißt …“ (TODO dc0566f8) and optionally a
 * project right away (c60bf914; more on the goal page) — the same form in the cockpit and on the goal overview.
 * `projectId` presets the project, e.g. from the cockpit's project filter.
 */
export function GoalCreateForm({ onCancel, onCreated, projectId: initialProject }: {
  onCancel: () => void;
  onCreated: (goal: Goal) => void;
  projectId?: string;
}) {
  const t = useT();
  const createGoal = useCreateGoal();
  const { data: projects = [] } = useProjects();
  const [projectId, setProjectId] = useState(initialProject ?? '');
  const choices = projects.filter((project) => !project.archivedAt);
  const descriptionId = useId();
  const [title, setTitle] = useState('');
  const [targetDate, setTargetDate] = useState('');
  const [description, setDescription] = useState('');

  const submit = () => {
    if (!title.trim()) return;
    createGoal.mutate(
      {
        title: title.trim(), targetDate: targetDate || undefined, description: description.trim() || undefined,
        ...(projectId ? { projectIds: [projectId] } : {}),
      },
      { onSuccess: onCreated },
    );
  };

  return (
    <Card className="mb-3 space-y-3">
      <Input label={t.goals.titleLabel} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
      <Input label={t.goals.targetDateLabel} type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
      {choices.length > 0 && (
        <Select label={t.goals.projects.optional} aria-label={t.goals.projects.optional} value={projectId}
          onChange={(e) => setProjectId(e.target.value)}
          options={[{ value: '', label: t.goals.projects.none }, ...choices.map((p) => ({ value: p.id, label: p.name }))]} />
      )}
      <div className="space-y-1.5">
        <label htmlFor={descriptionId} className="block text-sm font-medium text-text-secondary">
          {t.goals.descriptionLabel}
        </label>
        <textarea
          id={descriptionId}
          className="input-base w-full min-h-[64px] resize-y"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel}>{t.common.cancel}</Button>
        <Button onClick={submit} disabled={!title.trim() || createGoal.isPending}>{t.goals.save}</Button>
      </div>
    </Card>
  );
}
