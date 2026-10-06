import { useId } from 'react';
import { Target } from 'lucide-react';
import { Input, Select } from '../ui';
import { RatedByMark } from './GoalBadges';
import { useGoals } from '../../hooks/useGoals';
import { useI18nStore, useT } from '../../i18n';
import type { Todo } from '../../types';
import { rangeText } from './dragLabel';
import { packageRange } from './timelineLayout';
import { COMPLEXITIES, EFFORTS, packageOptions, withGoal } from './planning';
import type { TodoPlanningForm } from './useTodoPlanningForm';

/**
 * Planning of a TODO in the edit dialog (TODO dc0566f8): goal, work package (only those of the
 * TODO's project, c60bf914), complexity, recommended effort, reasoning, open question and
 * decision (68a5e24e). The dialog holds the state (`useTodoPlanningForm`); it is saved with
 * its „Speichern“ (bbee09e6), there is no separate button any more.
 */
export function TodoPlanningPanel({ todo, planning }: { todo: Todo; planning: TodoPlanningForm }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: goals = [] } = useGoals();
  const { form, setForm, conflict, loaded } = planning;
  const decisionId = useId();
  // Only a backend from 68a5e24e on knows decisions - an older one would silently drop the field
  const decisions = todo.planning?.decisionState !== undefined;
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
            // While the goal loads, the previous package stays selected instead of seemingly „none“
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
      {decisions && (
        <div className="space-y-1.5">
          <label htmlFor={decisionId} className="block text-sm font-medium text-text-secondary">{t.goals.decision.field}</label>
          <textarea id={decisionId} rows={2} value={form.decision} aria-describedby={`${decisionId}-hint`}
            onChange={(e) => setForm({ ...form, decision: e.target.value })} className="input-base w-full resize-y" />
          <p id={`${decisionId}-hint`} className={`text-xs ${conflict ? 'text-danger' : 'text-text-tertiary'}`} role={conflict ? 'alert' : undefined}>
            {conflict ? t.goals.decision.conflict : t.goals.decision.fieldHint}
          </p>
        </div>
      )}
    </fieldset>
  );
}
