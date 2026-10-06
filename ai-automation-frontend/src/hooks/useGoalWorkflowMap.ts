import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalWorkflowKeys } from './useGoalWorkflows';
import type { GoalRelations, GoalWorkflow, Todo } from '../types';

/**
 * Dropping in the workflow assignment (52bc1e8c). Every assignment shows immediately and offers
 * "Rückgängig" in the toast – this also works when the map has already been left (client and cache only).
 */

const relationsKey = (goalId: string) => ['goals', 'relations', goalId] as const;

/** Sets the fix workflow (LINKED) of a TODO in all stored maps of the goal. */
function patchRelations(qc: QueryClient, goalId: string, todoId: string, workflow: { id: string; name: string } | null) {
  qc.setQueriesData<GoalRelations>({ queryKey: relationsKey(goalId) }, (data) => data && {
    ...data,
    todos: data.todos.map((todo) => (todo.id !== todoId ? todo : {
      ...todo,
      workers: [
        ...(workflow ? [{ kind: 'LINKED' as const, workflowId: workflow.id, workflowName: workflow.name, triggerId: null, triggerLabel: null }] : []),
        ...todo.workers.filter((worker) => worker.kind !== 'LINKED'),
      ],
    })),
  });
}

/**
 * A TODO gets its fix workflow: `PUT /todos/{id}` with only `linkedWorkflowId` – the server
 * takes over only fields that are set (`TodoService.update`), the others stay as they are;
 * `''` removes the link.
 */
export function useLinkTodoWorkflow(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const tm = t.goals.workflowMap;
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: relationsKey(goalId) });
    void qc.invalidateQueries({ queryKey: ['todos'] });
  };
  const put = (todoId: string, workflowId: string) => api.put<Todo>(`/todos/${todoId}`, { linkedWorkflowId: workflowId });
  return useMutation({
    mutationFn: ({ todoId, workflow }: { todoId: string; todoTitle: string; workflow: { id: string; name: string }; previous: { id: string; name: string } | null }) =>
      put(todoId, workflow.id),
    onMutate: async ({ todoId, workflow }) => {
      await qc.cancelQueries({ queryKey: relationsKey(goalId) });
      const snapshot = qc.getQueriesData<GoalRelations>({ queryKey: relationsKey(goalId) });
      patchRelations(qc, goalId, todoId, workflow);
      return { snapshot };
    },
    onError: (err, _vars, context) => {
      context?.snapshot.forEach(([key, data]) => qc.setQueryData(key, data));
      toast.error(apiErrorMessage(err, tm.failed));
    },
    onSuccess: (_todo, { todoId, todoTitle, workflow, previous }) => {
      toast.success(tm.linked.replace('{workflow}', workflow.name).replace('{todo}', todoTitle), {
        label: tm.undo,
        onClick: async () => {
          // as in onMutate: a refetch still running (onSettled of the link) would otherwise bring back the
          // outdated state "linked" before the undo has reached the server
          await qc.cancelQueries({ queryKey: relationsKey(goalId) });
          patchRelations(qc, goalId, todoId, previous);
          try {
            await put(todoId, previous?.id ?? '');
            toast.success(tm.undone);
          } catch (err) {
            toast.error(apiErrorMessage(err, tm.failed));
          } finally {
            refresh();
          }
        },
      });
    },
    onSettled: refresh,
  });
}

/**
 * A workflow to the package or to the whole goal – like "Workflow zuordnen" (role WORKER); undo
 * removes the new assignment again.
 */
export function useAttachDroppedWorkflow(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const tm = t.goals.workflowMap;
  const refresh = () => void qc.invalidateQueries({ queryKey: goalWorkflowKeys.list(goalId) });
  return useMutation({
    mutationFn: ({ workflowId, workPackageId }: { workflowId: string; workPackageId: string | null; message: string }) =>
      api.post<GoalWorkflow>(`/goals/${goalId}/workflows`, { workflowId, role: 'WORKER', ...(workPackageId ? { workPackageId } : {}) }),
    onSuccess: (created, { message }) => {
      refresh();
      toast.success(message, {
        label: tm.undo,
        onClick: () => {
          void api.delete<void>(`/goals/${goalId}/workflows/${created.id}`)
            .then(() => toast.success(tm.undone))
            .catch((err) => toast.error(apiErrorMessage(err, tm.failed)))
            .finally(refresh);
        },
      });
    },
    onError: (err) => toast.error(apiErrorMessage(err, tm.failed)),
  });
}
