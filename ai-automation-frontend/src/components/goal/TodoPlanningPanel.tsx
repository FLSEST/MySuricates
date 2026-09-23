import { Target } from 'lucide-react';
import { Button, Input, Select } from '../ui';
import { RatedByMark } from './GoalBadges';
import { useSyncedState } from '../../hooks/useSyncedState';
import { useGoals, useUpdateTodoPlanning } from '../../hooks/useGoals';
import { useT } from '../../i18n';
import type { Todo } from '../../types';
import { COMPLEXITIES, EFFORTS, formOf, planningChanges } from './planning';

/**
 * Einordnung eines TODOs im Bearbeiten-Dialog (TODO dc0566f8): Ziel, Komplexität, empfohlener
 * Effort, Begründung und offene Frage. Speichert für sich, getrennt vom übrigen Formular.
 */
export function TodoPlanningPanel({ todo }: { todo: Todo }) {
  const t = useT();
  const { data: goals = [] } = useGoals();
  const update = useUpdateTodoPlanning();
  const [form, setForm] = useSyncedState(todo, formOf);
  const changes = planningChanges(formOf(todo), form);
  const dirty = Object.keys(changes).length > 0;

  return (
    <fieldset className="pt-3 border-t border-border space-y-3" data-testid="todo-planning">
      <legend className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
        <Target className="w-3.5 h-3.5" />
        {t.goals.planning}
        <RatedByMark ratedBy={todo.planning?.ratedBy ?? null} />
      </legend>
      <Select
        label={t.goals.goal}
        aria-label={t.goals.goal}
        value={form.goalId}
        onChange={(e) => setForm({ ...form, goalId: e.target.value })}
        options={[{ value: '', label: t.goals.noGoal }, ...goals.map((g) => ({ value: g.id, label: g.title }))]}
      />
      <div className="grid grid-cols-2 gap-3">
        <Select
          label={t.goals.complexity}
          aria-label={t.goals.complexity}
          title={t.goals.complexityHint}
          value={form.complexity}
          onChange={(e) => setForm({ ...form, complexity: e.target.value })}
          options={[{ value: '', label: t.goals.notSet }, ...COMPLEXITIES.map((c) => ({ value: c, label: c }))]}
        />
        <Select
          label={t.goals.effort}
          aria-label={t.goals.effort}
          title={t.goals.effortHint}
          value={form.suggestedEffort}
          onChange={(e) => setForm({ ...form, suggestedEffort: e.target.value })}
          options={[{ value: '', label: t.goals.notSet }, ...EFFORTS.map((e) => ({ value: e, label: e }))]}
        />
      </div>
      <Input label={t.goals.ratingNote} value={form.ratingNote} onChange={(e) => setForm({ ...form, ratingNote: e.target.value })} />
      <Input
        label={t.goals.openQuestion}
        hint={t.goals.openQuestionHint}
        value={form.openQuestion}
        onChange={(e) => setForm({ ...form, openQuestion: e.target.value })}
      />
      <div className="flex justify-end">
        <Button size="sm" variant="secondary" disabled={!dirty || update.isPending}
          onClick={() => update.mutate({ id: todo.id, data: changes })}>
          {t.goals.savePlanning}
        </Button>
      </div>
    </fieldset>
  );
}
