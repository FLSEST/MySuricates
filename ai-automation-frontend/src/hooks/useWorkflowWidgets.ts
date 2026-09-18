import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { parseWorkflowEntry, type WorkflowWidgetEntry } from '../components/dashboard/workflowWidgetContract';

/** Query key of the one workflow-widget request per dashboard — used to invalidate on a finished run. */
export const WORKFLOW_RESULTS_QUERY_KEY = ['dashboard', 'workflow-results'] as const;

interface WorkflowWidgetOptions {
  /** True while the run-completed socket is not connected: then, and only then, the data is polled. */
  polling: boolean;
}

/**
 * The latest run and the validated contract of every workflow shown in the current view — one
 * request per dashboard, not one per widget (spec E3). Updates arrive through the run-completed
 * broadcast; the 60 s interval is the fallback for a missing WebSocket connection.
 *
 * Every entry goes through parseWorkflowEntry: what does not match the contract never reaches a widget.
 */
export function useWorkflowWidgetResults(workflowIds: readonly string[], options: WorkflowWidgetOptions) {
  return useQuery({
    queryKey: [...WORKFLOW_RESULTS_QUERY_KEY, workflowIds.join(',')],
    enabled: workflowIds.length > 0,
    queryFn: async () => {
      const params = new URLSearchParams();
      for (const workflowId of workflowIds) params.append('workflowIds', workflowId);
      const raw = await api.get<unknown>(`/dashboard/workflow-results?${params.toString()}`);
      if (!Array.isArray(raw)) return [];
      return raw.map(parseWorkflowEntry).filter((entry): entry is WorkflowWidgetEntry => entry !== null);
    },
    refetchInterval: options.polling ? 60_000 : false,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
}
