import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import { useGoalReveal } from '../components/goal/goalReveal';
import type { GoalDetail, Todo } from '../types';

/**
 * Add TODOs to the goal (85889063, Flo: "sollte man dort ja einem Arbeitspaket zuordnen können").
 * Every add says in the toast where the TODO landed ("Hinzugefügt: Projekt · Paket"), with
 * "Zeigen" (jumps there in the outline or the timeline, the toast stays open) and "Rückgängig"
 * (a just-created TODO is archived, never deleted). This also works when
 * the goal page has already been left: only client and stores.
 */

/** Where it goes: a package (with title for the toast) or none. */
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

/** Created, but the assignment to the goal failed – the TODO exists, just not in the goal. */
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
  /** `planning` with `goalId` and package: one step – the backend attaches the project to the goal. */
  const place = (todoId: string, workPackage: PackageChoice | null) =>
    api.patch<Todo>(`/todos/${todoId}/planning`, { goalId, ...(workPackage ? { workPackageId: workPackage.id } : {}) });

  const announce = (todoId: string, projectName: string | null, workPackage: PackageChoice | null, undo: () => Promise<unknown>,
    undone: string) => {
    const where = `${projectName ?? t.goals.outline.noProject} · ${workPackage?.title ?? t.goals.outline.noWorkPackage}`;
    toast.success(ta.placed.replace('{where}', () => where), [
      // "Zeigen" leaves the toast open: look first, then still be able to undo
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
  // `goalId: ''` removes the assignment including rank and package; the project stays on the goal
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
      // Undo does not delete: detach from the goal and archive – nothing is lost, even if
      // a TodoTrigger has run in the meantime or someone has edited it (`PUT` only takes `status`)
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
