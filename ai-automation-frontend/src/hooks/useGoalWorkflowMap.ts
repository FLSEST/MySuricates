import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalWorkflowKeys } from './useGoalWorkflows';
import type { GoalRelations, GoalWorkflow, Todo } from '../types';

/**
 * Ablegen in der Workflow-Zuordnung (52bc1e8c). Jede Zuordnung zeigt sich sofort und bietet im Toast
 * „Rückgängig“ an – das läuft auch, wenn die Karte schon verlassen ist (nur Client und Cache).
 */

const relationsKey = (goalId: string) => ['goals', 'relations', goalId] as const;

/** Setzt den Fix-Workflow (LINKED) eines TODOs in allen gespeicherten Karten des Ziels. */
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
 * Ein TODO bekommt seinen Fix-Workflow: `PUT /todos/{id}` nur mit `linkedWorkflowId` – der Server
 * übernimmt ausschließlich gesetzte Felder (`TodoService.update`), die übrigen bleiben, wie sie sind;
 * `''` nimmt die Verknüpfung weg.
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
          // wie in onMutate: ein noch laufendes Nachladen (onSettled der Verknüpfung) brächte sonst den
          // überholten Stand „verknüpft“ zurück, bevor das Rückgängig beim Server angekommen ist
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
 * Ein Workflow ans Paket oder ans ganze Ziel – wie „Workflow zuordnen“ (Rolle WORKER); Rückgängig
 * löst die neue Zuordnung wieder.
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
