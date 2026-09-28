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
 * Workflows am Ziel oder Arbeitspaket (52bc1e8c): zuordnen, Rolle ändern, lösen, starten. Der letzte
 * Lauf kommt aus dem vorhandenen Lesepfad `/dashboard/workflow-results`.
 */
export const goalWorkflowKeys = {
  list: (goalId: string) => ['goals', 'workflows', goalId] as const,
};

export interface GoalWorkflowList {
  attachments: GoalWorkflow[];
  /** Das Backend kennt Workflows am Ziel. Ein älteres antwortet mit 404 – dann steht nur der Wächter da. */
  supported: boolean;
}

/**
 * Der Wächter, wie ihn ein älteres Backend nur über `goal.guard` kennt – wie beim neuen nur, solange
 * er eingeschaltet ist.
 */
function guardEntry(goalId: string, guard: GoalGuard, name: string): GoalWorkflow[] {
  return guard.enabled && guard.workflowId ? [{
    id: 'guard', goalId, workPackageId: null, workflowId: guard.workflowId, workflowName: name, workflowProjectId: null,
    role: 'STATUS', guard: true,
  }] : [];
}

/**
 * Die Workflows eines Ziels. Ohne Endpunkt (404, Backend vor 52bc1e8c) nicht wiederholen: Es bleibt
 * bei dem Wächter aus `goal.guard`, Zuordnen ist erst nach dem Neustart möglich.
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
 * Letzter Lauf und Ergebnis der Workflows am Ziel – derselbe Aufruf wie die Dashboard-Kacheln, im
 * selben Cache. Läuft einer gerade, wird alle 5 s nachgesehen, sonst jede Minute.
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

/** Schlüssel aller Starts an einem Ziel – daran erkennt jeder ▶, dass derselbe Workflow schon startet. */
const startKey = (goalId: string) => ['goals', goalId, 'start-workflow'] as const;

interface StartVariables {
  workflowId: string;
  workPackageId: string | null;
  name: string;
}

/**
 * Startet einen Workflow des Ziels über den vorhandenen Weg `POST /workflows/{id}/execute` – mit
 * `goal_id` (daraus kommen `goal_status` und `goal_guard_*`), am Paket auch `work_package_id`, und
 * der Sprache der Oberfläche. Der Toast führt zum Lauf.
 *
 * Gegen Doppelstarts (Review 52bc1e8c): Alle ▶ eines Ziels teilen sich über `mutationKey` einen Stand.
 * Solange ein Start desselben Workflows unterwegs ist, startet `start` nichts – geprüft im Cache der
 * Mutationen, der den neuen Start sofort kennt, also auch beim schnellen Doppelklick auf zwei Knöpfe.
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

/** Startet dieser Workflow gerade (irgendein ▶ des Ziels)? */
export function useWorkflowStarting(goalId: string, workflowId: string): boolean {
  const starting = useMutationState({
    filters: { mutationKey: startKey(goalId), status: 'pending' },
    select: (mutation) => (mutation.state.variables as StartVariables | undefined)?.workflowId,
  });
  return starting.includes(workflowId);
}

/**
 * Ein ▶ für einen Workflow des Ziels: gesperrt, solange derselbe Workflow gerade gestartet wird oder
 * sein letzter Lauf noch läuft – so entsteht aus zwei Knöpfen (Chip und Paket-Karte) nie ein zweiter Lauf.
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
