import { useId, useState } from 'react';
import { Button, Card, Input } from '../ui';
import { useCreateGoal } from '../../hooks/useGoals';
import { useT } from '../../i18n';
import type { Goal } from '../../types';

/**
 * Ein neues Ziel mit Titel, Zieldatum und „fertig heißt …“ (TODO dc0566f8) – im Cockpit und auf
 * der Zielübersicht dasselbe Formular.
 */
export function GoalCreateForm({ onCancel, onCreated }: { onCancel: () => void; onCreated: (goal: Goal) => void }) {
  const t = useT();
  const createGoal = useCreateGoal();
  const descriptionId = useId();
  const [title, setTitle] = useState('');
  const [targetDate, setTargetDate] = useState('');
  const [description, setDescription] = useState('');

  const submit = () => {
    if (!title.trim()) return;
    createGoal.mutate(
      { title: title.trim(), targetDate: targetDate || undefined, description: description.trim() || undefined },
      { onSuccess: onCreated },
    );
  };

  return (
    <Card className="mb-3 space-y-3">
      <Input label={t.goals.titleLabel} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
      <Input label={t.goals.targetDateLabel} type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
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
