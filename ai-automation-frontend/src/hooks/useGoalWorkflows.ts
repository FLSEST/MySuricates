import { useMutation, useMutationState, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api, apiErrorMessage, apiErrorStatus } from '../api/client';
import { useToast } from './useToast';
import { useModuleEnabled } from './useModules';
import { useI18nStore, useT } from '../i18n';
import { WORKFLOW_RESULTS_QUERY_KEY, fetchWorkflowResults } from './useWorkflowWidgets';
import { runActive, runPath } from '../components/goal/goalWorkflowStatus';
import type { WorkflowWidgetEntry } from '../components/dashboard/workflowWidgetContract';
import type { GoalGuard, GoalWorkflow, GoalWorkflowRole, WorkflowRun } from '../types';

/**
 * Workflows on the goal or work package (52bc1e8c): assign, change role, detach, start. The last
 * run comes from the existing read path `/dashboard/workflow-results`.
 */
export const goalWorkflowKeys = {
  list: (goalId: string) => ['goals', 'workflows', goalId] as const,
};

export interface GoalWorkflowList {
  attachments: GoalWorkflow[];
  /** The backend knows workflows on the goal. An older one answers with 404 – then only the guard is shown. */
  supported: boolean;
}

/**
 * The guard as an older backend knows it only via `goal.guard` – as with the new one, only as long as
 * it is switched on.
 */
function guardEntry(goalId: string, guard: GoalGuard, name: string): GoalWorkflow[] {
  return guard.enabled && guard.workflowId ? [{
    id: 'guard', goalId, workPackageId: null, workflowId: guard.workflowId, workflowName: name, workflowProjectId: null,
    role: 'STATUS', guard: true,
  }] : [];
}

/**
 * The workflows of a goal. Without the endpoint (404, backend before 52bc1e8c) do not retry: it stays
 * with the guard from `goal.guard`, assigning is only possible after the restart.
 */
export function useGoalWorkflows(goalId: string | undefined, guard: GoalGuard | undefined, guardName: string) {
  const goalsModule = useModuleEnabled('goals');
  return useQuery({
    queryKey: [...goalWorkflowKeys.list(goalId ?? ''), guard?.enabled && guard.workflowId ? guard.workflowId : ''],
    queryFn: async (): Promise<GoalWorkflowList> => {
      try {
        return { attachments: await api.get<GoalWorkflow[]>(`/goals/${goalId}/workflows`), supported: true };
      } catch (err) {
        if (apiErrorStatus(err) !== 404) throw err;
        return { attachments: guard ? guardEntry(goalId!, guard, guardName) : [], supported: false };
      }
    },
    enabled: goalsModule && !!goalId,
    staleTime: 30_000,
  });
}

const ACTIVE_RUN = new Set(['RUNNING', 'PENDING', 'PAUSED']);

/**
 * Last run and result of the workflows on the goal – the same call as the dashboard tiles, in the
 * same cache. If one is running right now, it is checked every 5 s, otherwise every minute.
 */
export function useGoalWorkflowResults(workflowIds: readonly string[]) {
  const ids = [...new Set(workflowIds)].sort();
  return useQuery({
    queryKey: [...WORKFLOW_RESULTS_QUERY_KEY, ids.join(',')],
    queryFn: () => fetchWorkflowResults(ids),
    enabled: ids.length > 0,
    staleTime: 10_000,
    refetchInterval: (query) => ((query.state.data as WorkflowWidgetEntry[] | undefined)
      ?.some((entry) => entry.run && ACTIVE_RUN.has(entry.run.status)) ? 5_000 : 60_000),
  });
}

function useRefreshGoalWorkflows(goalId: string) {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: goalWorkflowKeys.list(goalId) });
}

export function useAttachGoalWorkflow(goalId: string) {
  const toast = useToast();
  const t = useT();
  const refresh = useRefreshGoalWorkflows(goalId);
  return useMutation({
    mutationFn: (data: { workflowId: string; workPackageId?: string; role: GoalWorkflowRole }) =>
      api.post<GoalWorkflow>(`/goals/${goalId}/workflows`, data),
    onSuccess: () => {
      void refresh();
      toast.success(t.goals.workflows.attached);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workflows.saveFailed)),
  });
}

export function useUpdateGoalWorkflowRole(goalId: string) {
  const toast = useToast();
  const t = useT();
  const refresh = useRefreshGoalWorkflows(goalId);
  return useMutation({
    mutationFn: ({ id, role }: { id: string; role: GoalWorkflowRole }) =>
      api.patch<GoalWorkflow>(`/goals/${goalId}/workflows/${id}`, { role }),
    onSuccess: () => void refresh(),
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workflows.saveFailed)),
  });
}

export function useDetachGoalWorkflow(goalId: string) {
  const toast = useToast();
  const t = useT();
  const refresh = useRefreshGoalWorkflows(goalId);
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/goals/${goalId}/workflows/${id}`),
    onSuccess: () => {
      void refresh();
      toast.success(t.goals.workflows.detached);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workflows.saveFailed)),
  });
}

/** Key of all starts on a goal – by it every ▶ recognizes that the same workflow is already starting. */
const startKey = (goalId: string) => ['goals', goalId, 'start-workflow'] as const;

interface StartVariables {
  workflowId: string;
  workPackageId: string | null;
  name: string;
}

/**
 * Starts a workflow of the goal via the existing path `POST /workflows/{id}/execute` – with
 * `goal_id` (which yields `goal_status` and `goal_guard_*`), on the package also `work_package_id`, and
 * the UI language. The toast leads to the run.
 *
 * Against double starts (review 52bc1e8c): All ▶ of a goal share one state via `mutationKey`.
 * As long as a start of the same workflow is in flight, `start` does nothing – checked in the cache of
 * the mutations, which knows the new start immediately, so also on a quick double click on two buttons.
 */
export function useStartGoalWorkflow(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const mutation = useMutation({
    mutationKey: startKey(goalId),
    mutationFn: ({ workflowId, workPackageId }: StartVariables) =>
      api.post<WorkflowRun>(`/workflows/${workflowId}/execute`, {
        context: { goal_id: goalId, ...(workPackageId ? { work_package_id: workPackageId } : {}), goal_locale: locale },
      }),
    onSuccess: (run, { workflowId, name }) => {
      void qc.invalidateQueries({ queryKey: WORKFLOW_RESULTS_QUERY_KEY });
      void qc.invalidateQueries({ queryKey: ['workflows', workflowId, 'runs'] });
      toast.success(t.goals.workflows.started.replace('{name}', name), run?.id ? {
        label: t.goals.workflows.openRun, onClick: () => navigate(runPath(workflowId, run.id)),
      } : undefined);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workflows.startFailed)),
  });
  const start = (variables: StartVariables) => {
    const running = qc.getMutationCache().findAll({ mutationKey: startKey(goalId), status: 'pending' })
      .some((pending) => (pending.state.variables as StartVariables | undefined)?.workflowId === variables.workflowId);
    if (!running) mutation.mutate(variables);
  };
  return { start };
}

/** Is this workflow starting right now (any ▶ of the goal)? */
export function useWorkflowStarting(goalId: string, workflowId: string): boolean {
  const starting = useMutationState({
    filters: { mutationKey: startKey(goalId), status: 'pending' },
    select: (mutation) => (mutation.state.variables as StartVariables | undefined)?.workflowId,
  });
  return starting.includes(workflowId);
}

/**
 * A ▶ for a workflow of the goal: disabled as long as the same workflow is being started or
 * its last run is still running – so two buttons (chip and package card) never produce a second run.
 */
export function useGoalWorkflowStart(goalId: string, workflowId: string, workPackageId: string | null, name: string,
  entry: WorkflowWidgetEntry | null | undefined) {
  const { start } = useStartGoalWorkflow(goalId);
  const starting = useWorkflowStarting(goalId, workflowId);
  const running = runActive(entry);
  return {
    starting,
    running,
    busy: starting || running,
    start: () => {
      if (!running) start({ workflowId, workPackageId, name });
    },
  };
}
