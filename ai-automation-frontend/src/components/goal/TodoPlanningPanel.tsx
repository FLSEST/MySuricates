import { Target } from 'lucide-react';
import { Button, Input, Select } from '../ui';
import { RatedByMark } from './GoalBadges';
import { useSyncedState } from '../../hooks/useSyncedState';
import { useGoal, useGoals, useUpdateTodoPlanning } from '../../hooks/useGoals';
import { useI18nStore, useT } from '../../i18n';
import type { Todo } from '../../types';
import { rangeText } from './dragLabel';
import { packageRange } from './timelineLayout';
import { COMPLEXITIES, EFFORTS, forBackend, formOf, packageOptions, planningChanges, withGoal } from './planning';

/**
 * Einordnung eines TODOs im Bearbeiten-Dialog (TODO dc0566f8): Ziel, Arbeitspaket (nur solche des
 * TODO-Projekts, c60bf914), Komplexität, empfohlener Effort, Begründung und offene Frage. Speichert
 * für sich, getrennt vom übrigen Formular.
 */
export function TodoPlanningPanel({ todo }: { todo: Todo }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: goals = [] } = useGoals();
  const update = useUpdateTodoPlanning();
  const [form, setForm] = useSyncedState(todo, formOf);
  const changes = planningChanges(formOf(todo), form);
  const dirty = Object.keys(changes).length > 0;
  const { data: goal } = useGoal(form.goalId || undefined);
  const loaded = goal && goal.goal.id === form.goalId ? goal : undefined;
  const packages = loaded ? packageOptions(loaded.workPackages, todo.projectId, form.workPackageId) : [];
  const packageLabel = (item: (typeof packages)[number]) => {
    const range = packageRange(item);
    return `${item.title} · ${range ? rangeText(range, locale) : t.goals.workPackages.noRange}`;
  };

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
        onChange={(e) => setForm(withGoal(form, e.target.value))}
        options={[{ value: '', label: t.goals.noGoal }, ...goals.map((g) => ({ value: g.id, label: g.title }))]}
      />
      {form.goalId && (
        <Select
          label={t.goals.workPackage}
          aria-label={t.goals.workPackage}
          value={form.workPackageId}
          onChange={(e) => setForm({ ...form, workPackageId: e.target.value })}
          options={[
            { value: '', label: t.goals.timeline.noWorkPackageOption },
            ...packages.map((item) => ({ value: item.id, label: packageLabel(item) })),
            // Solange das Ziel lädt, bleibt das bisherige Paket gewählt statt scheinbar „keins“
            ...(form.workPackageId && !packages.some((item) => item.id === form.workPackageId)
              ? [{ value: form.workPackageId, label: t.common.loading }] : []),
          ]}
        />
      )}
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
          onClick={() => update.mutate({ id: todo.id, data: forBackend(changes, loaded?.legacyBackend) })}>
          {t.goals.savePlanning}
        </Button>
      </div>
    </fieldset>
  );
}
