import { useSyncedState } from '../../hooks/useSyncedState';
import { useGoal, useUpdateTodoPlanning } from '../../hooks/useGoals';
import type { Todo } from '../../types';
import { EMPTY_PLANNING, forBackend, formOf, planningChanges, planningConflict, type PlanningForm } from './planning';

/**
 * Einordnung eines TODOs im Bearbeiten-Dialog als Formularzustand des Dialogs (bbee09e6): Es gibt
 * nur noch das eine „Speichern“ – es schickt erst das TODO (PUT), danach mit `save` die geänderten
 * Felder der Einordnung (PATCH /todos/{id}/planning). Vorher verwarf „Speichern“ eine nicht
 * eigens gespeicherte Einordnung still. `todo` null = Dialog zu oder Modul „Ziele“ aus.
 */
export function useTodoPlanningForm(todo: Todo | null) {
  const update = useUpdateTodoPlanning();
  // Neu abgeleitet nur beim Wechsel des TODOs (Öffnen, anderes TODO), nicht bei jedem neuen Objekt: Der
  // Refetch nach dem PUT hätte sonst bei gescheitertem PATCH die Eingaben verworfen (bbee09e6, Review)
  const [form, setForm] = useSyncedState<PlanningForm, string | null>(
    todo?.id ?? null, (id, prev) => (id && todo ? formOf(todo) : prev ?? EMPTY_PLANNING),
  );
  const changes = todo ? planningChanges(formOf(todo), form) : {};
  const dirty = Object.keys(changes).length > 0;
  const conflict = planningConflict(changes);
  const { data: goal } = useGoal(todo && form.goalId ? form.goalId : undefined);
  const loaded = goal && goal.goal.id === form.goalId ? goal : undefined;

  /**
   * Speichert nur geänderte Felder (sonst gälte eine unveränderte KI-Einschätzung als menschlich);
   * ohne Änderung kein Aufruf. Scheitert der Aufruf, weist das Promise ab – der Dialog bleibt offen.
   */
  const save = async (): Promise<void> => {
    if (!todo || !dirty) return;
    await update.mutateAsync({ id: todo.id, data: forBackend(changes, loaded?.legacyBackend) });
  };

  return { form, setForm, dirty, conflict, loaded, saving: update.isPending, save };
}

export type TodoPlanningForm = ReturnType<typeof useTodoPlanningForm>;
