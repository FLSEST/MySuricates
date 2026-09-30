import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import { useGoalReveal } from '../components/goal/goalReveal';
import type { GoalDetail, Todo } from '../types';

/**
 * TODOs zum Ziel hinzufügen (85889063, Flo: „sollte man dort ja einem Arbeitspaket zuordnen können“).
 * Jedes Hinzufügen sagt im Toast, wo das TODO gelandet ist („Hinzugefügt: Projekt · Paket“), mit
 * „Zeigen“ (springt in der Gliederung bzw. im Zeitstrahl hin, der Toast bleibt offen) und „Rückgängig“
 * (ein eben angelegtes TODO wird archiviert, nie gelöscht). Das läuft auch, wenn
 * die Zielseite schon verlassen ist: nur Client und Stores.
 */

/** Wohin es geht: ein Paket (mit Titel für den Toast) oder keins. */
export interface PackageChoice {
  id: string;
  title: string;
}

export interface AddExisting {
  todoId: string;
  projectName: string | null;
  workPackage: PackageChoice | null;
}

export interface AddNew {
  title: string;
  projectId: string | null;
  projectName: string | null;
  workPackage: PackageChoice | null;
}

/** Angelegt, aber die Zuordnung zum Ziel scheiterte – das TODO gibt es, nur nicht im Ziel. */
class CreatedButNotPlaced extends Error {
  constructor(readonly todo: Todo, readonly cause: unknown) {
    super('created but not placed');
  }
}

export function useAddTodoToGoal(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const ta = t.goals.add;
  const reveal = useGoalReveal((state) => state.reveal);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: goalKeys.all });
    void qc.invalidateQueries({ queryKey: ['todos'] });
  };
  /** `planning` mit `goalId` und Paket: ein Schritt – das Backend hängt das Projekt ans Ziel. */
  const place = (todoId: string, workPackage: PackageChoice | null) =>
    api.patch<Todo>(`/todos/${todoId}/planning`, { goalId, ...(workPackage ? { workPackageId: workPackage.id } : {}) });

  const announce = (todoId: string, projectName: string | null, workPackage: PackageChoice | null, undo: () => Promise<unknown>,
    undone: string) => {
    const where = `${projectName ?? t.goals.outline.noProject} · ${workPackage?.title ?? t.goals.outline.noWorkPackage}`;
    toast.success(ta.placed.replace('{where}', () => where), [
      // „Zeigen“ lässt den Toast offen: erst hinschauen, dann noch zurücknehmen können
      { label: ta.show, onClick: () => reveal(goalId, todoId), keepOpen: true },
      {
        label: t.goals.undoAssign,
        onClick: () => {
          void undo()
            .then(() => toast.success(undone))
            .catch((err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)))
            .finally(refresh);
        },
      },
    ]);
  };
  // `goalId: ''` löst die Zuordnung samt Rang und Paket; das Projekt bleibt am Ziel
  const unassign = (todoId: string) => api.patch<Todo>(`/todos/${todoId}/planning`, { goalId: '' });

  const existing = useMutation({
    mutationFn: async ({ todoId, workPackage }: AddExisting): Promise<GoalDetail | Todo> =>
      workPackage ? place(todoId, workPackage) : api.post<GoalDetail>(`/goals/${goalId}/todos`, { todoIds: [todoId] }),
    onSuccess: (result, { todoId, projectName, workPackage }) => {
      if (!workPackage && 'goal' in result) qc.setQueryData(goalKeys.detail(goalId), normalizeGoalDetail(result));
      refresh();
      announce(todoId, projectName, workPackage, () => unassign(todoId), t.goals.assignUndone);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
  });

  const created = useMutation({
    mutationFn: async ({ title, projectId, workPackage }: AddNew): Promise<Todo> => {
      const todo = await api.post<Todo>('/todos', { title, ...(projectId ? { projectId } : {}) });
      try {
        await place(todo.id, workPackage);
      } catch (err) {
        throw new CreatedButNotPlaced(todo, err);
      }
      return todo;
    },
    onSuccess: (todo, { projectName, workPackage }) => {
      refresh();
      // Rückgängig löscht nicht: vom Ziel lösen und archivieren – nichts geht verloren, auch wenn
      // inzwischen ein TodoTrigger lief oder jemand es bearbeitet hat (`PUT` übernimmt nur `status`)
      announce(todo.id, projectName, workPackage, async () => {
        await unassign(todo.id);
        await api.put<Todo>(`/todos/${todo.id}`, { status: 'ARCHIVED' });
      }, ta.archived);
    },
    onError: (err) => {
      if (err instanceof CreatedButNotPlaced) {
        refresh();
        toast.error(ta.createdNotPlaced.replace('{title}', () => err.todo.title)
          .replace('{reason}', () => apiErrorMessage(err.cause, t.goals.saveFailed)));
        return;
      }
      toast.error(apiErrorMessage(err, t.goals.saveFailed));
    },
  });

  return {
    addExisting: existing.mutate,
    addNew: created.mutate,
    isPending: existing.isPending || created.isPending,
  };
}
