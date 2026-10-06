import { useSyncedState } from '../../hooks/useSyncedState';
import { useGoal, useUpdateTodoPlanning } from '../../hooks/useGoals';
import type { Todo } from '../../types';
import { EMPTY_PLANNING, forBackend, formOf, planningChanges, planningConflict, type PlanningForm } from './planning';

/**
 * Planning of a TODO in the edit dialog as the dialog's form state (bbee09e6): there is
 * only the one "Speichern" - it first sends the TODO (PUT), then with `save` the changed
 * planning fields (PATCH /todos/{id}/planning). Before, "Speichern" silently discarded a planning
 * that was not saved separately. `todo` null = dialog closed or module "Ziele" off.
 */
export function useTodoPlanningForm(todo: Todo | null) {
  const update = useUpdateTodoPlanning();
  // Re-derived only when the TODO changes (opening, another TODO), not on every new object: the
  // refetch after the PUT would otherwise have discarded the input if the PATCH failed (bbee09e6, review)
  const [form, setForm] = useSyncedState<PlanningForm, string | null>(
    todo?.id ?? null, (id, prev) => (id && todo ? formOf(todo) : prev ?? EMPTY_PLANNING),
  );
  const changes = todo ? planningChanges(formOf(todo), form) : {};
  const dirty = Object.keys(changes).length > 0;
  const conflict = planningConflict(changes);
  const { data: goal } = useGoal(todo && form.goalId ? form.goalId : undefined);
  const loaded = goal && goal.goal.id === form.goalId ? goal : undefined;

  /**
   * Saves only changed fields (otherwise an unchanged AI estimate would count as human);
   * no call without changes. If the call fails, the promise rejects - the dialog stays open.
   */
  const save = async (): Promise<void> => {
    if (!todo || !dirty) return;
    await update.mutateAsync({ id: todo.id, data: forBackend(changes, loaded?.legacyBackend) });
  };

  return { form, setForm, dirty, conflict, loaded, saving: update.isPending, save };
}

export type TodoPlanningForm = ReturnType<typeof useTodoPlanningForm>;
